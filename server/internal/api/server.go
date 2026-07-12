// Package api — HTTP-поверхность Message Service (api-overview.md §Сообщения и ключи).
//
// Конверты ходят как protobuf (Content-Type: application/x-protobuf); JSON в ответах —
// обвязка с base64url для бинарных полей (api-overview.md §Общее).
//
// АВТОРИЗАЦИЯ (MVP): устройство берётся из заголовка X-Device-Id — это dev-заглушка
// до фазы Auth (device JWT Bearer). Наружу в таком виде не выставлять.
package api

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"

	"google.golang.org/protobuf/proto"

	timacrypto "tima/server/internal/crypto"
	pb "tima/server/internal/proto"
	"tima/server/internal/store"
)

const maxEnvelopeBytes = 4 << 20 // конверт с payload; медиа ходят через MinIO, не сюда

type Server struct {
	Store *store.Store
}

func (s *Server) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/messages", s.postMessage)
	mux.HandleFunc("GET /api/v1/chats/{chatID}/messages", s.listMessages)
	mux.HandleFunc("POST /api/v1/dev/devices", s.registerDeviceDev)
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
	deviceID := r.Header.Get("X-Device-Id")
	if deviceID == "" {
		writeErr(w, http.StatusUnauthorized, "no_device", "нет X-Device-Id (dev-авторизация MVP)")
		return
	}
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
		Envelope   string `json:"envelope"`    // base64url(protobuf Envelope) с единственной обёрткой устройства
		WrappedKey string `json:"wrapped_key"` // base64url — дублирует обёртку из конверта для удобства
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
		out = append(out, item{MessageID: m.MessageID, Envelope: b64.EncodeToString(raw), WrappedKey: b64.EncodeToString(m.WrappedKeyForDevice)})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"messages": out})
}

// registerDeviceDev — dev-регистрация устройства (до фазы Auth; в бою — /auth/register).
func (s *Server) registerDeviceDev(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DeviceID      string `json:"device_id"`
		UserID        string `json:"user_id"`
		EncryptionPub string `json:"encryption_pub"` // base64url, 32 B
		SigningPub    string `json:"signing_pub"`    // base64url, 32 B
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	enc, err1 := base64.RawURLEncoding.DecodeString(req.EncryptionPub)
	sig, err2 := base64.RawURLEncoding.DecodeString(req.SigningPub)
	if err1 != nil || err2 != nil || len(enc) != 32 || len(sig) != 32 || req.DeviceID == "" || req.UserID == "" {
		writeErr(w, http.StatusBadRequest, "bad_keys", "нужны device_id, user_id и ключи по 32 байта (base64url)")
		return
	}
	if err := s.Store.RegisterDevice(r.Context(), store.Device{
		DeviceID: req.DeviceID, UserID: req.UserID, EncryptionPub: enc, SigningPub: sig,
	}); err != nil {
		log.Printf("registerDeviceDev: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.WriteHeader(http.StatusCreated)
}
