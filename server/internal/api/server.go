// Package api — HTTP-поверхность бэкенда (api-overview.md).
//
// Конверты ходят как protobuf (Content-Type: application/x-protobuf); JSON в ответах —
// обвязка с base64url для бинарных полей (api-overview.md §Общее).
// Авторизация — Bearer device JWT (internal/auth); auth-эндпоинты публичные.
package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"sync"
	"time"

	"google.golang.org/protobuf/proto"

	"tima/server/internal/auth"
	"tima/server/internal/blob"
	"tima/server/internal/calls"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/events"
	pb "tima/server/internal/proto"
	"tima/server/internal/ratelimit"
	"tima/server/internal/store"
)

const maxEnvelopeBytes = 4 << 20 // конверт с payload; медиа ходят через MinIO, не сюда

type Server struct {
	Store  *store.Store
	Auth   *auth.Issuer
	Blob   *blob.Client       // nil → media-эндпоинты отвечают 503
	Events *events.Bus        // nil → /ws отвечает 503, доставка только REST-историей
	Limit  *ratelimit.Limiter // nil → без лимитов частоты (dev без Redis)
	DevSMS bool               // TIMA_DEV_SMS=1: код из /auth/sms/request возвращается в ответе

	// Переопределение лимитов auth (0 → прод-дефолт). Для dev/тестов, где с одного
	// IP регистрируется много устройств (иначе rate limit ложно срабатывает).
	SMSPerPhone, SMSPerIP, VerifyPerCode int

	// Звонки: LiveKit-токены (nil → /calls отвечает 503). LiveKitURL клиент получает
	// для подключения к SFU.
	Calls      *calls.Issuer
	LiveKitURL string

	// EscrowURL — адрес stub-анклава (ESCROW_URL); "" → /escrow/pubkey отвечает 503
	EscrowURL     string
	escrowMu      sync.Mutex
	escrowCached  []byte
	escrowFetched time.Time
}

func (s *Server) Register(mux *http.ServeMux) {
	// Публичные (до токена)
	mux.HandleFunc("POST /api/v1/auth/sms/request", s.smsRequest)
	mux.HandleFunc("POST /api/v1/auth/sms/verify", s.smsVerify)
	mux.HandleFunc("POST /api/v1/auth/register", s.register)
	// Под device JWT
	mux.HandleFunc("POST /api/v1/messages", s.Auth.Require(s.postMessage))
	mux.HandleFunc("GET /api/v1/chats/{chatID}/messages", s.Auth.Require(s.listMessages))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/recover", s.Auth.Require(s.chatRecover))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/recover/provide", s.Auth.Require(s.chatRecoverProvide))
	mux.HandleFunc("POST /api/v1/chats/{chatID}/backup", s.Auth.Require(s.chatBackupSave))
	mux.HandleFunc("GET /api/v1/chats/{chatID}/backup", s.Auth.Require(s.chatBackupList))
	mux.HandleFunc("GET /api/v1/keys/devices", s.Auth.Require(s.listDeviceKeys))
	mux.HandleFunc("GET /api/v1/users/lookup", s.Auth.Require(s.lookupUser))
	mux.HandleFunc("PATCH /api/v1/users/me/name", s.Auth.Require(s.setDisplayName))
	mux.HandleFunc("POST /api/v1/users/names", s.Auth.Require(s.resolveNames))
	mux.HandleFunc("GET /api/v1/escrow/pubkey", s.Auth.Require(s.escrowPubkey))
	mux.HandleFunc("POST /api/v1/groups", s.Auth.Require(s.createGroup))
	mux.HandleFunc("GET /api/v1/groups", s.Auth.Require(s.listMyGroups))
	mux.HandleFunc("GET /api/v1/groups/{groupID}", s.Auth.Require(s.getGroup))
	mux.HandleFunc("PATCH /api/v1/groups/{groupID}", s.Auth.Require(s.patchGroup))
	mux.HandleFunc("DELETE /api/v1/groups/{groupID}", s.Auth.Require(s.deleteGroup))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/members", s.Auth.Require(s.listGroupMembers))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/members", s.Auth.Require(s.addGroupMember))
	mux.HandleFunc("DELETE /api/v1/groups/{groupID}/members/{userID}", s.Auth.Require(s.removeGroupMember))
	mux.HandleFunc("PUT /api/v1/groups/{groupID}/members/{userID}/role", s.Auth.Require(s.setGroupRole))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/members/{userID}/ban", s.Auth.Require(s.banGroupMember))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/messages", s.Auth.Require(s.postGroupMessage))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/messages", s.Auth.Require(s.listGroupMessages))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys", s.Auth.Require(s.groupRotate))
	mux.HandleFunc("GET /api/v1/groups/{groupID}/keys", s.Auth.Require(s.groupKeys))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys/recover", s.Auth.Require(s.groupKeyRecover))
	mux.HandleFunc("POST /api/v1/groups/{groupID}/keys/recover/provide", s.Auth.Require(s.groupKeyProvide))
	mux.HandleFunc("POST /api/v1/media/init", s.Auth.Require(s.mediaInit))
	mux.HandleFunc("POST /api/v1/media/complete", s.Auth.Require(s.mediaComplete))
	mux.HandleFunc("GET /api/v1/media/{mediaID}/url", s.Auth.Require(s.mediaURL))
	mux.HandleFunc("POST /api/v1/channels", s.Auth.Require(s.createChannel))
	mux.HandleFunc("GET /api/v1/channels", s.Auth.Require(s.listMyChannels))
	mux.HandleFunc("GET /api/v1/channels/discover", s.Auth.Require(s.discoverChannels))
	mux.HandleFunc("POST /api/v1/channels/{channelID}/subscribe", s.Auth.Require(s.subscribeChannel))
	mux.HandleFunc("DELETE /api/v1/channels/{channelID}/subscribe", s.Auth.Require(s.unsubscribeChannel))
	mux.HandleFunc("POST /api/v1/channels/{channelID}/posts", s.Auth.Require(s.postToChannel))
	mux.HandleFunc("GET /api/v1/channels/{channelID}/posts", s.Auth.Require(s.listChannelPosts))
	mux.HandleFunc("POST /api/v1/calls", s.Auth.Require(s.startCall))
	mux.HandleFunc("POST /api/v1/calls/{callID}/answer", s.Auth.Require(s.answerCall))
	mux.HandleFunc("POST /api/v1/calls/{callID}/end", s.Auth.Require(s.endCall))
	mux.HandleFunc("POST /api/v1/voice-rooms", s.Auth.Require(s.createVoiceRoom))
	mux.HandleFunc("GET /api/v1/voice-rooms", s.Auth.Require(s.listVoiceRooms))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/join", s.Auth.Require(s.joinVoiceRoom))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/hand", s.Auth.Require(s.raiseHand))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/grant", s.Auth.Require(s.grantSpeaker))
	mux.HandleFunc("POST /api/v1/voice-rooms/{roomID}/revoke", s.Auth.Require(s.revokeSpeaker))
	mux.HandleFunc("GET /ws", s.handleWS) // auth — первым кадром, не Bearer (websocket-events.md)
}

// notify — доставка события устройству (sync-offline.md §2): сначала в
// персистентный device_events (источник догона sync.pull), затем — live через
// Redis Pub/Sub, если шина есть. Ошибка live-доставки не фатальна: событие уже
// в логе, устройство заберёт его при следующем sync.pull.
func (s *Server) notify(ctx context.Context, deviceID, event string, payload map[string]any) {
	raw, err := json.Marshal(payload)
	if err != nil {
		log.Printf("notify %s %s: marshal: %v", deviceID, event, err)
		return
	}
	eventID, err := s.Store.AppendDeviceEvent(ctx, deviceID, event, raw)
	if err != nil {
		log.Printf("notify %s %s: append: %v", deviceID, event, err)
		return
	}
	if s.Events != nil {
		if err := s.Events.Publish(ctx, deviceID, event, eventID, payload); err != nil {
			log.Printf("notify %s %s: publish: %v", deviceID, event, err)
		}
	}
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"code": code, "message": msg})
}

// postMessage — приём конверта: protobuf → валидация размеров → подпись → хранение.
func (s *Server) postMessage(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(io.LimitReader(r.Body, maxEnvelopeBytes+1))
	if err != nil || len(body) > maxEnvelopeBytes {
		writeErr(w, http.StatusRequestEntityTooLarge, "envelope_too_large", "конверт больше 4 MiB")
		return
	}
	var env pb.Envelope
	if err := proto.Unmarshal(body, &env); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_protobuf", "конверт не парсится")
		return
	}
	if msg := validateEnvelope(&env); msg != "" {
		writeErr(w, http.StatusBadRequest, "bad_envelope", msg)
		return
	}
	meta := env.GetMeta()

	// Отправитель конверта обязан совпадать с владельцем токена: чужим именем не подписаться
	id, _ := auth.FromContext(r.Context())
	if meta.GetSenderId() != id.UserID || meta.GetSenderDevice() != id.DeviceID {
		writeErr(w, http.StatusForbidden, "sender_mismatch", "sender_id/sender_device не совпадают с токеном")
		return
	}

	// Подпись: ключ устройства отправителя обязан существовать и принадлежать sender_id
	signingPub, err := s.Store.SigningKey(r.Context(), meta.GetSenderDevice(), meta.GetSenderId())
	if errors.Is(err, store.ErrDeviceUnknown) {
		writeErr(w, http.StatusForbidden, "unknown_device", "устройство отправителя не зарегистрировано")
		return
	} else if err != nil {
		log.Printf("postMessage: signing key: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	cb := timacrypto.CanonicalBytes(env.GetFormatVersion(), timacrypto.EnvelopeMeta{
		MessageID:       meta.GetMessageId(),
		ChatID:          meta.GetChatId(),
		SenderID:        meta.GetSenderId(),
		SenderDevice:    meta.GetSenderDevice(),
		Kind:            uint32(meta.GetKind()),
		CreatedAtUnixMs: meta.GetCreatedAtUnixMs(),
		ReplyTo:         meta.GetReplyTo(),
	},
		env.GetEncryptedPayload(),
		append(append([]byte{}, env.GetEscrow().GetMlkemCt()...), env.GetEscrow().GetWrappedMessageKey()...),
		env.GetSenderEphemeralPub(),
		env.GetRatchetEnvelope(),
	)
	if !timacrypto.VerifyEnvelopeSignature(signingPub, cb, env.GetSignature()) {
		writeErr(w, http.StatusForbidden, "bad_signature", "подпись конверта не прошла проверку")
		return
	}

	wrapped := make(map[string][]byte, len(env.GetWrappedKeys()))
	for _, wk := range env.GetWrappedKeys() {
		wrapped[wk.GetRecipient()] = wk.GetWrapped()
	}
	clientMsgID := r.Header.Get("X-Client-Msg-Id") // дедупликация повторной отправки (api-overview: client_msg_id)
	if clientMsgID == "" {
		writeErr(w, http.StatusBadRequest, "no_client_msg_id", "нужен заголовок X-Client-Msg-Id (UUID)")
		return
	}
	err = s.Store.SaveMessage(r.Context(), store.Message{
		ChatID:             meta.GetChatId(),
		MessageID:          meta.GetMessageId(),
		ClientMsgID:        clientMsgID,
		SenderID:           meta.GetSenderId(),
		SenderDevice:       meta.GetSenderDevice(),
		Kind:               int32(meta.GetKind()),
		CreatedAtUnixMs:    meta.GetCreatedAtUnixMs(),
		ReplyTo:            meta.GetReplyTo(),
		FormatVersion:      int32(env.GetFormatVersion()),
		EncryptedPayload:   env.GetEncryptedPayload(),
		EscrowMlkemCt:      env.GetEscrow().GetMlkemCt(),
		EscrowWrappedKey:   env.GetEscrow().GetWrappedMessageKey(),
		EscrowKeyVersion:   int32(env.GetEscrow().GetEscrowKeyVersion()),
		SenderEphemeralPub: env.GetSenderEphemeralPub(),
		RatchetEnvelope:    env.GetRatchetEnvelope(),
		Signature:          env.GetSignature(),
		WrappedKeys:        wrapped,
	})
	if errors.Is(err, store.ErrDuplicate) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"duplicate": true, "message_id": meta.GetMessageId()})
		return
	} else if err != nil {
		log.Printf("postMessage: save: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// Доставка адресатам: event log (+ live онлайн-устройствам) — конверт
	// с единственной обёрткой адресата. Push-очередь офлайн — итерация worker-а.
	for _, wk := range env.GetWrappedKeys() {
		single := proto.Clone(&env).(*pb.Envelope)
		single.WrappedKeys = []*pb.WrappedKey{wk}
		raw, err := proto.Marshal(single)
		if err != nil {
			continue
		}
		s.notify(r.Context(), wk.GetRecipient(), "message.new", map[string]any{
			"chat_id":    meta.GetChatId(),
			"message_id": meta.GetMessageId(),
			"envelope":   base64.RawURLEncoding.EncodeToString(raw),
		})
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"message_id": meta.GetMessageId()})
}

// validateEnvelope — жёсткие инварианты wire-формата (envelope.proto). Пустая строка = ок.
func validateEnvelope(env *pb.Envelope) string {
	switch {
	case env.GetFormatVersion() != timacrypto.FormatVersion:
		return "неподдерживаемый format_version"
	case env.GetMeta() == nil:
		return "нет meta"
	case env.GetMeta().GetChatId() == "" || env.GetMeta().GetSenderId() == "" || env.GetMeta().GetSenderDevice() == "":
		return "пустые идентификаторы meta"
	case len(env.GetEncryptedPayload()) < 24+16: // nonce + MAC SecretBox
		return "encrypted_payload короче минимума SecretBox"
	case env.GetEscrow() == nil:
		return "нет escrow (ADR-0004: escrow обязателен)"
	case len(env.GetEscrow().GetMlkemCt()) != 1088:
		return "escrow.mlkem_ct должен быть 1088 байт (ML-KEM-768)"
	case len(env.GetEscrow().GetWrappedMessageKey()) < 24+16+32:
		return "escrow.wrapped_message_key короче обёрнутого ключа"
	case len(env.GetSenderEphemeralPub()) != 32:
		return "sender_ephemeral_pub должен быть 32 байта (X25519)"
	case len(env.GetSignature()) != 64:
		return "signature должна быть 64 байта (Ed25519)"
	case len(env.GetWrappedKeys()) == 0:
		return "нет wrapped_keys (план Б обязателен)"
	}
	for _, wk := range env.GetWrappedKeys() {
		if wk.GetRecipient() == "" || len(wk.GetWrapped()) < 24+16+32 {
			return "некорректный wrapped_key"
		}
	}
	return ""
}

// listMessages — история чата: конверт (protobuf, base64url) + wrapped_key устройства.
func (s *Server) listMessages(w http.ResponseWriter, r *http.Request) {
	id, _ := auth.FromContext(r.Context())
	deviceID := id.DeviceID
	var before uint64
	if v := r.URL.Query().Get("before"); v != "" {
		before, _ = strconv.ParseUint(v, 10, 64)
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))

	msgs, err := s.Store.ListMessages(r.Context(), r.PathValue("chatID"), deviceID, before, limit)
	if err != nil {
		log.Printf("listMessages: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	b64 := base64.RawURLEncoding
	type item struct {
		MessageID  uint64 `json:"message_id"`
		Envelope   string `json:"envelope"`                 // base64url(protobuf Envelope) с единственной обёрткой устройства
		WrappedKey string `json:"wrapped_key"`              // base64url — дублирует обёртку из конверта для удобства
		WrapEph    string `json:"wrap_ephemeral,omitempty"` // эфемерал обёртки восстановления (иначе — sender_ephemeral_pub конверта)
	}
	out := make([]item, 0, len(msgs))
	for _, m := range msgs {
		env := &pb.Envelope{
			FormatVersion: uint32(m.FormatVersion),
			Meta: &pb.Metadata{
				MessageId:       m.MessageID,
				ChatId:          m.ChatID,
				SenderId:        m.SenderID,
				SenderDevice:    m.SenderDevice,
				Kind:            pb.ContentKind(m.Kind),
				CreatedAtUnixMs: m.CreatedAtUnixMs,
				ReplyTo:         m.ReplyTo,
			},
			EncryptedPayload: m.EncryptedPayload,
			Escrow: &pb.EscrowBlob{
				MlkemCt:           m.EscrowMlkemCt,
				WrappedMessageKey: m.EscrowWrappedKey,
				EscrowKeyVersion:  uint32(m.EscrowKeyVersion),
			},
			SenderEphemeralPub: m.SenderEphemeralPub,
			RatchetEnvelope:    m.RatchetEnvelope,
			Signature:          m.Signature,
			WrappedKeys:        []*pb.WrappedKey{{Recipient: deviceID, Wrapped: m.WrappedKeyForDevice}},
		}
		raw, err := proto.Marshal(env)
		if err != nil {
			log.Printf("listMessages: marshal: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка сериализации")
			return
		}
		it := item{MessageID: m.MessageID, Envelope: b64.EncodeToString(raw), WrappedKey: b64.EncodeToString(m.WrappedKeyForDevice)}
		if len(m.WrapEphemeral) == 32 {
			it.WrapEph = b64.EncodeToString(m.WrapEphemeral)
		}
		out = append(out, it)
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"messages": out})
}

