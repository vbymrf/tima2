// Сообщения групп (api-overview.md §Группы (переписка)): POST/GET
// /groups/{id}/messages. Private-группа: payload = SecretBox(zstd(MessageBody), GK),
// gk_version обязателен; публичная: plaintext protobuf, gk_version отсутствует.
// Подпись Ed25519 устройства по group_message_canonical_bytes
// (schema/proto/README.md; KAT group_message_canonical) проверяется при приёме.
//
// Премодерация (pending) — когда data-model §4 получит колонку статуса;
// сообщения от имени сущности (sender_type='entity', боты) — вместе с Bot API.
package api

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/store"
)

// postGroupMessage — POST /groups/{groupID}/messages.
func (s *Server) postGroupMessage(w http.ResponseWriter, r *http.Request) {
	g, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	if role == "" {
		writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
		return
	}
	groupID := r.PathValue("groupID")
	id, _ := auth.FromContext(r.Context())

	// Бан и slow mode (модераторам и выше slow mode не действует)
	_, bannedUntil, err := s.Store.GroupMemberInfo(r.Context(), groupID, id.UserID)
	if err != nil {
		log.Printf("postGroupMessage: member info: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if bannedUntil != nil && bannedUntil.After(time.Now()) {
		writeErr(w, http.StatusForbidden, "banned", "участник заблокирован до "+bannedUntil.UTC().Format(time.RFC3339))
		return
	}
	if g.SlowModeSec > 0 && roleRank[role] < rankModerator {
		recent, err := s.Store.SenderPostedWithin(r.Context(), groupID, id.UserID, g.SlowModeSec)
		if err != nil {
			log.Printf("postGroupMessage: slow mode: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if recent {
			w.Header().Set("Retry-After", strconv.Itoa(int(g.SlowModeSec)))
			writeErr(w, http.StatusTooManyRequests, "slow_mode", "подождите slow mode")
			return
		}
	}

	var req struct {
		ClientMsgID     string `json:"client_msg_id"`
		Kind            uint32 `json:"kind"`
		GKVersion       int32  `json:"gk_version"`
		Payload         string `json:"payload"` // base64url
		ThreadRoot      int64  `json:"thread_root"`
		ReplyTo         int64  `json:"reply_to"`
		CreatedAtUnixMs int64  `json:"created_at_unix_ms"`
		Signature       string `json:"signature"` // base64url, Ed25519
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxEnvelopeBytes+64<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	if req.ClientMsgID == "" {
		writeErr(w, http.StatusBadRequest, "no_client_msg_id", "нужен client_msg_id (UUID)")
		return
	}
	b64 := base64.RawURLEncoding
	payload, err1 := b64.DecodeString(req.Payload)
	signature, err2 := b64.DecodeString(req.Signature)
	if err1 != nil || err2 != nil || len(signature) != 64 {
		writeErr(w, http.StatusBadRequest, "bad_encoding", "payload/signature — base64url, подпись 64 байта")
		return
	}
	if len(payload) == 0 || len(payload) > maxEnvelopeBytes {
		writeErr(w, http.StatusBadRequest, "bad_payload", "payload пуст или больше 4 MiB")
		return
	}
	if req.ThreadRoot < 0 || req.ReplyTo < 0 || req.GKVersion < 0 {
		writeErr(w, http.StatusBadRequest, "bad_refs", "thread_root/reply_to/gk_version не могут быть отрицательными")
		return
	}

	// Крипто-инварианты по типу группы
	if g.Kind == "private" {
		if req.GKVersion == 0 {
			writeErr(w, http.StatusBadRequest, "no_gk_version", "private-группа: нужен gk_version")
			return
		}
		if len(payload) < 24+16 {
			writeErr(w, http.StatusBadRequest, "bad_payload", "payload короче минимума SecretBox")
			return
		}
		known, err := s.Store.GroupKeyVersionExists(r.Context(), groupID, req.GKVersion)
		if err != nil {
			log.Printf("postGroupMessage: gk version: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !known {
			writeErr(w, http.StatusBadRequest, "unknown_gk_version", "такой версии GK у группы нет")
			return
		}
	} else if req.GKVersion != 0 {
		writeErr(w, http.StatusBadRequest, "gk_in_public", "public-группа: без gk_version")
		return
	}

	// Ссылки веток/ответов — на сообщения этой же группы
	for name, ref := range map[string]int64{"thread_root": req.ThreadRoot, "reply_to": req.ReplyTo} {
		if ref == 0 {
			continue
		}
		exists, err := s.Store.GroupMessageExists(r.Context(), groupID, ref)
		if err != nil {
			log.Printf("postGroupMessage: ref %s: %v", name, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !exists {
			writeErr(w, http.StatusBadRequest, "bad_"+name, name+" не найден в группе")
			return
		}
	}

	// Подпись: устройство из токена, preimage — group_message_canonical_bytes
	signingPub, err := s.Store.SigningKey(r.Context(), id.DeviceID, id.UserID)
	if errors.Is(err, store.ErrDeviceUnknown) {
		writeErr(w, http.StatusForbidden, "unknown_device", "устройство отправителя не зарегистрировано")
		return
	} else if err != nil {
		log.Printf("postGroupMessage: signing key: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	cb := timacrypto.GroupMessageCanonicalBytes(timacrypto.GroupMessageMeta{
		GroupID:         groupID,
		SenderID:        id.UserID,
		SenderDevice:    id.DeviceID,
		Kind:            req.Kind,
		CreatedAtUnixMs: req.CreatedAtUnixMs,
		ThreadRoot:      uint64(req.ThreadRoot),
		ReplyTo:         uint64(req.ReplyTo),
		GKVersion:       uint32(req.GKVersion),
	}, payload)
	if !timacrypto.VerifyEnvelopeSignature(signingPub, cb, signature) {
		writeErr(w, http.StatusForbidden, "bad_signature", "подпись сообщения не прошла проверку")
		return
	}

	msg := store.GroupMessage{
		GroupID:         groupID,
		ClientMsgID:     req.ClientMsgID,
		SenderID:        id.UserID,
		SenderDevice:    id.DeviceID,
		Kind:            int32(req.Kind),
		GKVersion:       req.GKVersion,
		Payload:         payload,
		ThreadRoot:      req.ThreadRoot,
		ReplyTo:         req.ReplyTo,
		CreatedAtUnixMs: req.CreatedAtUnixMs,
		Signature:       signature,
	}
	messageID, duplicate, err := s.Store.SaveGroupMessage(r.Context(), msg)
	if err != nil {
		log.Printf("postGroupMessage: save: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if duplicate {
		_ = json.NewEncoder(w).Encode(map[string]any{"duplicate": true, "message_id": messageID})
		return
	}
	msg.MessageID = messageID

	// Live-доставка онлайн-устройствам активных участников (кроме устройства отправителя)
	if s.Events != nil {
		devices, err := s.Store.ActiveMemberDevices(r.Context(), groupID, id.DeviceID)
		if err != nil {
			log.Printf("postGroupMessage: member devices: %v", err)
		}
		payload := groupMessageJSON(msg)
		for _, dev := range devices {
			if err := s.Events.Publish(r.Context(), dev, "message.group", payload); err != nil {
				log.Printf("postGroupMessage: publish %s: %v", dev, err)
			}
		}
	}

	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"message_id": messageID})
}

// groupMessageJSON — сообщение в JSON-обвязке (все поля preimage — получатель
// обязан суметь проверить подпись).
func groupMessageJSON(m store.GroupMessage) map[string]any {
	b64 := base64.RawURLEncoding
	return map[string]any{
		"message_id":         m.MessageID,
		"group_id":           m.GroupID,
		"sender_id":          m.SenderID,
		"sender_device":      m.SenderDevice,
		"kind":               m.Kind,
		"gk_version":         m.GKVersion,
		"payload":            b64.EncodeToString(m.Payload),
		"thread_root":        m.ThreadRoot,
		"reply_to":           m.ReplyTo,
		"created_at_unix_ms": m.CreatedAtUnixMs,
		"signature":          b64.EncodeToString(m.Signature),
	}
}

// listGroupMessages — GET /groups/{groupID}/messages?before=&limit=&thread=.
func (s *Server) listGroupMessages(w http.ResponseWriter, r *http.Request) {
	_, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	if role == "" {
		writeErr(w, http.StatusNotFound, "group_not_found", "группа не найдена")
		return
	}
	var before, thread int64
	if v := r.URL.Query().Get("before"); v != "" {
		before, _ = strconv.ParseInt(v, 10, 64)
	}
	if v := r.URL.Query().Get("thread"); v != "" {
		thread, _ = strconv.ParseInt(v, 10, 64)
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))

	msgs, err := s.Store.ListGroupMessages(r.Context(), r.PathValue("groupID"), thread, before, limit)
	if err != nil {
		log.Printf("listGroupMessages: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	out := make([]map[string]any, 0, len(msgs))
	for _, m := range msgs {
		out = append(out, groupMessageJSON(m))
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"messages": out})
}
