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
	// Rooms — управление комнатами LiveKit (закрыть, выкинуть участника).
	// nil → «завершить звонок» меняет только наше состояние, комната живёт до
	// empty_timeout, и клиент, не услышавший уведомление, продолжает публиковать.
	Rooms *calls.RoomClient

	// EscrowURL — адрес stub-анклава (ESCROW_URL); "" → /escrow/pubkey отвечает 503
	EscrowURL string
	// EscrowRegion — измерение региона в реестре ключей; "" → "ru". Заведено сразу,
	// хотя значение пока одно: добавить измерение позже — миграция всех ссылок.
	EscrowRegion string
	// EscrowOverlap — за сколько до конца эпохи клиенту отдаётся и следующий ключ.
	// 0 → неделя. Без перекрытия смена эпохи останавливает отправку у клиентов с
	// закэшированным конфигом.
	EscrowOverlap time.Duration
	escrowMu      sync.Mutex
	escrowCached  []byte
	escrowFetched time.Time

	// AppVer — последняя версия клиента для авто-обновления (nil → /app/version отдаёт 204)
	AppVer *AppVersion
}

// requireActiveDevice verifies both the device JWT and the device's current
// server-side status. JWTs are otherwise valid until expiry after a revoke.
func (s *Server) requireActiveDevice(next http.HandlerFunc) http.HandlerFunc {
	return s.Auth.Require(func(w http.ResponseWriter, r *http.Request) {
		id, ok := auth.FromContext(r.Context())
		if !ok {
			writeErr(w, http.StatusUnauthorized, "unauthorized", "нужна авторизация")
			return
		}
		active, err := s.Store.IsActiveDevice(r.Context(), id.UserID, id.DeviceID)
		if err != nil {
			log.Printf("requireActiveDevice: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !active {
			writeErr(w, http.StatusUnauthorized, "device_revoked", "устройство отозвано")
			return
		}
		next(w, r)
	})
}

func (s *Server) Register(mux *http.ServeMux) {
	// Публичные (до токена)
	mux.HandleFunc("GET /api/v1/app/version", s.appVersion)
	mux.HandleFunc("POST /api/v1/auth/sms/request", s.smsRequest)
	mux.HandleFunc("POST /api/v1/auth/sms/verify", s.smsVerify)
	mux.HandleFunc("POST /api/v1/auth/register", s.register)
	// Под device JWT
	// Личные сообщения и состояния переписки (шаг 4).
	RegisterMessages(mux, s.Store, func() Publisher {
		if s.Events == nil {
			return nil
		}
		return s.Events
	}, s.notifier(), s.requireActiveDevice)
	// Чаты: архив, копии и восстановление истории (шаг 4).
	RegisterChats(mux, s.Store, s.notifier(), s.requireActiveDevice)
	mux.HandleFunc("GET /api/v1/keys/devices", s.requireActiveDevice(s.listDeviceKeys))
	// Люди и аккаунт (шаг 4): справочник, имена, личности, удаление.
	RegisterUsers(mux, s.Store, func() IdentityTokens { return s.Auth }, s.requireActiveDevice)
	// Устройства и привязка по QR (шаг 4). link/start и link/claim идут без
	// requireDevice: их зовёт устройство, у которого токена ещё нет.
	RegisterDevices(mux, s.Store, func() *ratelimit.Limiter { return s.Limit },
		func() TokenIssuer { return s.Auth }, s.notifier(), s.requireActiveDevice)
	mux.HandleFunc("GET /api/v1/escrow/pubkey", s.requireActiveDevice(s.escrowPubkey))
	mux.HandleFunc("GET /api/v1/escrow/key", s.requireActiveDevice(s.escrowKeyForChat))
	// Группы: состав, сообщения и ключи (шаг 4). Три файла держатся вместе
	// инвариантом ротации: смена состава обязана менять ключ.
	RegisterGroups(mux, s.Store, func() *ratelimit.Limiter { return s.Limit }, s.notifier(), s.requireActiveDevice)
	// Медиа (шаг 4): вместе с маршрутами уехало поле Blob.
	RegisterMedia(mux, s.Store, func() *blob.Client { return s.Blob }, s.requireActiveDevice)
	// Каналы — первая группа, вынесенная в registrar (шаг 4 программы). Дальше
	// сюда добавляются вызовы Register<Группа>, а не строки маршрутов.
	RegisterChannels(mux, s.Store, s.notifier(), s.requireActiveDevice)
	// Страница человека: своя лента и перенос к себе. Лента — канал, который ищут по
	// человеку, поэтому регистратор стоит рядом с каналами, а не с группами.
	RegisterFeeds(mux, s.Store, s.requireActiveDevice)
	RegisterVirtuals(mux, s.Store, s.requireActiveDevice)
	// Звонки, групповые звонки и аудио-комнаты (шаг 4): сюда же уехали поля
	// Calls, Rooms и LiveKitURL — их видят только эти двенадцать маршрутов.
	RegisterCalls(mux, s.Store, s.livekitSettings, s.notifier(), s.requireActiveDevice)
	mux.HandleFunc("GET /ws", s.handleWS) // auth — первым кадром, не Bearer (websocket-events.md)
}

// livekitSettings — снимок полей звонков НА МОМЕНТ ВЫЗОВА. Передаётся функцией, а
// не значением: cmd/tima и тесты заполняют эти поля уже после Register.
func (s *Server) livekitSettings() LiveKitSettings {
	return LiveKitSettings{Issuer: s.Calls, Rooms: s.Rooms, URL: s.LiveKitURL}
}

// notifier — уведомитель для registrar-ов: тот же порядок доставки, что у notify,
// но без доступа к остальным полям Server.
func (s *Server) notifier() *Notifier {
	return &Notifier{store: s.Store, bus: func() Publisher {
		// Проверка на nil здесь, а не в Notifier: s.Events — указатель, и
		// nil-указатель, положенный в интерфейс, перестаёт быть nil при
		// сравнении. Publish на таком дал бы панику вместо «шины нет».
		if s.Events == nil {
			return nil
		}
		return s.Events
	}}
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
func postMessage(deps messagesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
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
		signingPub, err := deps.store.SigningKey(r.Context(), meta.GetSenderDevice(), meta.GetSenderId())
		if errors.Is(err, store.ErrDeviceUnknown) {
			writeErr(w, http.StatusForbidden, "unknown_device", "устройство отправителя не зарегистрировано")
			return
		} else if err != nil {
			log.Printf("postMessage: signing key: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		cb := timacrypto.CanonicalBytesV2(env.GetFormatVersion(), timacrypto.EnvelopeMeta{
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
			env.GetKeyCommitment(),
		)
		if !timacrypto.VerifyEnvelopeSignature(signingPub, cb, env.GetSignature()) {
			writeErr(w, http.StatusForbidden, "bad_signature", "подпись конверта не прошла проверку")
			return
		}

		wrapped := make(map[string][]byte, len(env.GetWrappedKeys()))
		recipients := make([]string, 0, len(env.GetWrappedKeys()))
		for _, wk := range env.GetWrappedKeys() {
			wrapped[wk.GetRecipient()] = wk.GetWrapped()
			recipients = append(recipients, wk.GetRecipient())
		}

		// chat_id обязан быть выведен из пары «отправитель и собеседник».
		//
		// **Зачем.** Идентификатор личной переписки сервер не назначает — его считает
		// клиент из двух user_id (personalChatID, раскладка общая с Kotlin). Без этой
		// проверки посторонний, знающий оба user_id (их отдаёт справочник) и публичные
		// ключи чужих устройств (их отдаёт /keys/devices), клал бы своё сообщение
		// ВНУТРЬ чужой переписки. Прочитать её он и так не может, подделать отправителя
		// не даёт подпись, — но появиться в чужой ветке со своим именем мог.
		//
		// **Собеседник берётся из получателей**, а не из chat_id: chat_id и есть то,
		// что проверяется, и доверять ему при проверке самого себя нельзя.
		if len(recipients) > 0 {
			owners, err := deps.store.UsersOfDevices(r.Context(), recipients)
			if err != nil {
				log.Printf("postMessage: владельцы устройств: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			peers := make(map[string]struct{}, 2)
			for _, user := range owners {
				if user != id.UserID {
					peers[user] = struct{}{}
				}
			}
			switch len(peers) {
			case 0:
				// Получатели — только свои устройства. Либо это чат с самим собой, либо
				// у собеседника нет ни одного живого устройства. Проверять нечего и,
				// главное, некого защищать: в чужую переписку так не попасть.
			case 1:
				var peer string
				for user := range peers {
					peer = user
				}
				if meta.GetChatId() != personalChatID(id.UserID, peer) {
					writeErr(w, http.StatusForbidden, "chat_id_mismatch",
						"chat_id не выведен из пары отправителя и получателя")
					return
				}
			default:
				// Личная переписка — это двое. Конверт, завёрнутый на устройства
				// нескольких разных людей, — это уже группа, и путь у неё свой.
				writeErr(w, http.StatusBadRequest, "too_many_peers",
					"в личной переписке один собеседник; для нескольких есть группы")
				return
			}
		}
		clientMsgID := r.Header.Get("X-Client-Msg-Id") // дедупликация повторной отправки (api-overview: client_msg_id)
		if clientMsgID == "" {
			writeErr(w, http.StatusBadRequest, "no_client_msg_id", "нужен заголовок X-Client-Msg-Id (UUID)")
			return
		}
		err = deps.store.SaveMessage(r.Context(), store.Message{
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
			KeyCommitment:      env.GetKeyCommitment(),
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
			deps.notifier.Device(r.Context(), wk.GetRecipient(), "message.new", map[string]any{
				"chat_id":    meta.GetChatId(),
				"message_id": meta.GetMessageId(),
				"envelope":   base64.RawURLEncoding.EncodeToString(raw),
			})
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"message_id": meta.GetMessageId()})
	}
}

// validateEnvelope — жёсткие инварианты wire-формата (envelope.proto). Пустая строка = ок.
func validateEnvelope(env *pb.Envelope) string {
	switch {
	// Принимаем и старую версию: пока в пути есть сообщения от необновлённых
	// клиентов, отказ означал бы их потерю. Отзовём приём v1 отдельным релизом,
	// когда все перейдут (двухфазная выкатка, ADR-0013).
	case env.GetFormatVersion() != timacrypto.FormatVersion && env.GetFormatVersion() != timacrypto.FormatVersionLegacy:
		return "неподдерживаемый format_version"
	// Сервер не знает message_key и проверить ЗНАЧЕНИЕ обязательства не может —
	// но обязан требовать его наличия и правильной длины. Само значение защищено
	// подписью: оно входит в canonical_bytes.
	case env.GetFormatVersion() >= timacrypto.FormatVersion && len(env.GetKeyCommitment()) != timacrypto.CommitmentSize:
		return "key_commitment должен быть 32 байта при format_version >= 2"
	case env.GetFormatVersion() < timacrypto.FormatVersion && len(env.GetKeyCommitment()) != 0:
		return "key_commitment не место в конверте версии 1"
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
func listMessages(deps messagesDeps) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		deviceID := id.DeviceID
		var before uint64
		if v := r.URL.Query().Get("before"); v != "" {
			before, _ = strconv.ParseUint(v, 10, 64)
		}
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))

		msgs, err := deps.store.ListMessages(r.Context(), r.PathValue("chatID"), deviceID, before, limit)
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
				KeyCommitment:    m.KeyCommitment,
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
}
