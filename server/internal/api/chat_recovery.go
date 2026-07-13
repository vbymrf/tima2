// Восстановление истории личного чата (ADR-0010 §этап 2). Устройство без ключей
// старых сообщений запрашивает их у помощников — своих устройств (авто) или у
// собеседника (с согласия на клиенте). Помощник перезаворачивает message_key под
// новое устройство; сервер кладёт обёртки в personal_message_keys и уведомляет.
// Аутентификация запроса — device JWT + подпись ключом личности (если установлен).
package api

import (
	"encoding/base64"
	"encoding/json"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	"tima/server/internal/store"
)

// chatBackupSave — POST /chats/{chatID}/backup: владелец кладёт резервные обёртки
// ключей сообщений под свой backup_key (ADR-0010 §этап 4, «сообщения себе»).
func (s *Server) chatBackupSave(w http.ResponseWriter, r *http.Request) {
	chatID := r.PathValue("chatID")
	id, _ := auth.FromContext(r.Context())
	participant, err := s.Store.IsChatParticipant(r.Context(), chatID, id.UserID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if !participant {
		writeErr(w, http.StatusForbidden, "not_participant", "бэкап доступен только участнику чата")
		return
	}
	var req struct {
		Items []struct {
			MessageID uint64 `json:"message_id"`
			Wrapped   string `json:"wrapped"`
		} `json:"items"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<20)).Decode(&req); err != nil || len(req.Items) == 0 {
		writeErr(w, http.StatusBadRequest, "bad_json", "нужны items")
		return
	}
	b64 := base64.RawURLEncoding
	items := make([]store.MessageBackup, 0, len(req.Items))
	for _, it := range req.Items {
		wrapped, derr := b64.DecodeString(it.Wrapped)
		if derr != nil || len(wrapped) < 24+16 {
			writeErr(w, http.StatusBadRequest, "bad_wrapped", "некорректная обёртка бэкапа")
			return
		}
		items = append(items, store.MessageBackup{MessageID: it.MessageID, Wrapped: wrapped})
	}
	if err := s.Store.SaveMessageBackups(r.Context(), chatID, id.UserID, items); err != nil {
		log.Printf("chatBackupSave: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"saved": len(items)})
}

// chatBackupList — GET /chats/{chatID}/backup: резервные обёртки владельца
// (новое устройство разворачивает их backup_key из фразы и переносит историю).
func (s *Server) chatBackupList(w http.ResponseWriter, r *http.Request) {
	chatID := r.PathValue("chatID")
	id, _ := auth.FromContext(r.Context())
	participant, err := s.Store.IsChatParticipant(r.Context(), chatID, id.UserID)
	if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if !participant {
		writeErr(w, http.StatusForbidden, "not_participant", "бэкап доступен только участнику чата")
		return
	}
	items, err := s.Store.ListMessageBackups(r.Context(), chatID, id.UserID)
	if err != nil {
		log.Printf("chatBackupList: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	b64 := base64.RawURLEncoding
	type item struct {
		MessageID uint64 `json:"message_id"`
		Wrapped   string `json:"wrapped"`
	}
	out := make([]item, 0, len(items))
	for _, it := range items {
		out = append(out, item{MessageID: it.MessageID, Wrapped: b64.EncodeToString(it.Wrapped)})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"items": out})
}

// chatRecover — POST /chats/{chatID}/recover: запрос восстановления истории личного чата.
func (s *Server) chatRecover(w http.ResponseWriter, r *http.Request) {
	chatID := r.PathValue("chatID")
	id, _ := auth.FromContext(r.Context())

	participant, err := s.Store.IsChatParticipant(r.Context(), chatID, id.UserID)
	if err != nil {
		log.Printf("chatRecover: participant: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if !participant {
		writeErr(w, http.StatusForbidden, "not_participant", "восстановление доступно только участнику чата")
		return
	}

	// Подпись ключом личности (этап 3), если он установлен у аккаунта
	var req struct {
		Signature string `json:"signature"`
	}
	_ = json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&req)
	identityPub, err := s.Store.IdentityPub(r.Context(), id.UserID)
	if err != nil {
		log.Printf("chatRecover: identity: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if len(identityPub) == 32 {
		sig, derr := base64.RawURLEncoding.DecodeString(req.Signature)
		if derr != nil || !timacrypto.VerifyEnvelopeSignature(identityPub, recoverCanonical(chatID, id.DeviceID), sig) {
			writeErr(w, http.StatusForbidden, "bad_identity_sig", "запрос не подписан ключом личности аккаунта")
			return
		}
	}

	helpers, err := s.Store.ChatHelperDevices(r.Context(), chatID, id.DeviceID, id.UserID)
	if err != nil {
		log.Printf("chatRecover: helpers: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	encPub, err := s.Store.DeviceEncryptionPub(r.Context(), id.DeviceID)
	if err != nil {
		log.Printf("chatRecover: enc pub: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	b64 := base64.RawURLEncoding
	own := 0
	for _, h := range helpers {
		if h.Own {
			own++
		}
		s.notify(r.Context(), h.DeviceID, "recovery.msg_request", map[string]any{
			"chat_id":           chatID,
			"requester_device":  id.DeviceID,
			"requester_enc_pub": b64.EncodeToString(encPub),
			"own":               h.Own, // свои устройства помогают без согласия
		})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"helpers": len(helpers), "own_helpers": own})
}

// chatRecoverProvide — POST /chats/{chatID}/recover/provide: помощник отдаёт обёртки
// message_key под устройство-запросившее.
func (s *Server) chatRecoverProvide(w http.ResponseWriter, r *http.Request) {
	chatID := r.PathValue("chatID")
	id, _ := auth.FromContext(r.Context())

	participant, err := s.Store.IsChatParticipant(r.Context(), chatID, id.UserID)
	if err != nil {
		log.Printf("chatRecoverProvide: participant: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if !participant {
		writeErr(w, http.StatusForbidden, "not_participant", "делиться ключами может только участник чата")
		return
	}
	var req struct {
		RequesterDevice string `json:"requester_device"`
		Keys            []struct {
			MessageID          uint64 `json:"message_id"`
			SenderEphemeralPub string `json:"sender_ephemeral_pub"`
			Wrapped            string `json:"wrapped"`
		} `json:"keys"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 8<<20)).Decode(&req); err != nil || req.RequesterDevice == "" || len(req.Keys) == 0 {
		writeErr(w, http.StatusBadRequest, "bad_json", "нужны requester_device и keys")
		return
	}
	ok, err := s.Store.IsChatParticipantDevice(r.Context(), chatID, req.RequesterDevice)
	if err != nil {
		log.Printf("chatRecoverProvide: requester check: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if !ok {
		writeErr(w, http.StatusBadRequest, "not_participant_device", "получатель — не устройство участника чата")
		return
	}
	b64 := base64.RawURLEncoding
	keys := make([]store.RecoveryMessageKey, 0, len(req.Keys))
	for _, k := range req.Keys {
		eph, err1 := b64.DecodeString(k.SenderEphemeralPub)
		wrapped, err2 := b64.DecodeString(k.Wrapped)
		if err1 != nil || err2 != nil || len(eph) != 32 || len(wrapped) < 24+16+32 {
			writeErr(w, http.StatusBadRequest, "bad_key", "некорректная обёртка восстановления")
			return
		}
		keys = append(keys, store.RecoveryMessageKey{MessageID: k.MessageID, SenderEphemeralPub: eph, Wrapped: wrapped})
	}
	if err := s.Store.SaveRecoveryMessageKeys(r.Context(), chatID, req.RequesterDevice, keys); err != nil {
		log.Printf("chatRecoverProvide: save: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	s.notify(r.Context(), req.RequesterDevice, "recovery.msg_ready", map[string]any{
		"chat_id": chatID, "count": len(keys),
	})
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"saved": len(keys)})
}
