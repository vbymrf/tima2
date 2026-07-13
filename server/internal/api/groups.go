// API групповых ключей (api-overview.md §Группы; crypto-protocol.md §4).
// Серверная сторона клиентского GroupKeyManager: приём ротации GK и выдача
// пропущенных wrapped_GK. Сервер сами GK не видит — только escrow и обёртки.
// Права: ротирует owner|admin private-группы (crypto-protocol §4.2: GK
// генерирует админ-устройство); получатели обёрток — устройства активных
// участников (membership — group_service.go).
package api

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// groupRotate — POST /groups/{groupID}/keys: новая версия GK (строго current+1).
func (s *Server) groupRotate(w http.ResponseWriter, r *http.Request) {
	var req struct {
		GKVersion          int32  `json:"gk_version"`
		Reason             string `json:"reason,omitempty"` // periodic|member_join|member_leave|compromise
		SenderEphemeralPub string `json:"sender_ephemeral_pub"`
		Escrow             struct {
			MlkemCt           string `json:"mlkem_ct"`
			WrappedMessageKey string `json:"wrapped_message_key"`
			EscrowKeyVersion  int32  `json:"escrow_key_version"`
		} `json:"escrow"`
		WrappedKeys []struct {
			Recipient string `json:"recipient"`
			Wrapped   string `json:"wrapped"`
		} `json:"wrapped_keys"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	b64 := base64.RawURLEncoding
	ephPub, err := b64.DecodeString(req.SenderEphemeralPub)
	if err != nil || len(ephPub) != 32 {
		writeErr(w, http.StatusBadRequest, "bad_eph", "sender_ephemeral_pub — 32 байта base64url")
		return
	}
	mlkemCt, err1 := b64.DecodeString(req.Escrow.MlkemCt)
	escrowWrapped, err2 := b64.DecodeString(req.Escrow.WrappedMessageKey)
	if err1 != nil || err2 != nil || len(mlkemCt) != 1088 || len(escrowWrapped) < 24+16+32 {
		writeErr(w, http.StatusBadRequest, "bad_escrow", "escrow: mlkem_ct 1088 байт, wrapped ≥ 72 байт")
		return
	}
	if len(req.WrappedKeys) == 0 {
		writeErr(w, http.StatusBadRequest, "no_wrapped", "ротация без wrapped_keys бессмысленна")
		return
	}
	wrapped := make(map[string][]byte, len(req.WrappedKeys))
	for _, wk := range req.WrappedKeys {
		raw, err := b64.DecodeString(wk.Wrapped)
		if err != nil || wk.Recipient == "" || len(raw) < 24+16+32 {
			writeErr(w, http.StatusBadRequest, "bad_wrapped", "некорректный wrapped_GK")
			return
		}
		wrapped[wk.Recipient] = raw
	}
	reason := req.Reason
	if reason == "" {
		reason = "periodic"
	}

	// Права: группа существует, private, ротирующий — owner|admin
	g, role, ok := s.groupAndRole(w, r)
	if !ok {
		return
	}
	if g.Kind != "private" {
		writeErr(w, http.StatusBadRequest, "not_e2e", "GK есть только у private-групп")
		return
	}
	if roleRank[role] < rankAdmin {
		writeErr(w, http.StatusForbidden, "not_group_admin", "GK ротируют owner и admin группы")
		return
	}
	// Получатели wrapped_GK — действующие устройства активных участников
	recipients := make([]string, 0, len(wrapped))
	for rcpt := range wrapped {
		recipients = append(recipients, rcpt)
	}
	outsiders, err := s.Store.NonMemberDevices(r.Context(), r.PathValue("groupID"), recipients)
	if err != nil {
		log.Printf("groupRotate: non-member devices: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if len(outsiders) > 0 {
		writeErr(w, http.StatusBadRequest, "recipient_not_member",
			"wrapped_keys содержат устройства не-участников: "+strings.Join(outsiders, ", "))
		return
	}

	id, _ := auth.FromContext(r.Context())
	err = s.Store.SaveGroupRotation(r.Context(), store.GroupRotation{
		GroupID:            r.PathValue("groupID"),
		GKVersion:          req.GKVersion,
		RotatedBy:          id.UserID,
		SenderEphemeralPub: ephPub,
		EscrowMlkemCt:      mlkemCt,
		EscrowWrappedKey:   escrowWrapped,
		EscrowKeyVersion:   req.Escrow.EscrowKeyVersion,
		Reason:             reason,
		WrappedKeys:        wrapped,
	})
	if errors.Is(err, store.ErrVersionConflict) {
		writeErr(w, http.StatusConflict, "version_conflict", err.Error())
		return
	} else if err != nil {
		log.Printf("groupRotate: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// key.rotated участникам: event log + live онлайн-устройствам (websocket-events.md)
	for recipient, w := range wrapped {
		s.notify(r.Context(), recipient, "key.rotated", map[string]any{
			"group_id":             r.PathValue("groupID"),
			"gk_version":           req.GKVersion,
			"sender_ephemeral_pub": req.SenderEphemeralPub,
			"wrapped_gk":           base64.RawURLEncoding.EncodeToString(w),
		})
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"group_id": r.PathValue("groupID"), "gk_version": req.GKVersion})
}

// groupKeyRecover — POST /groups/{groupID}/keys/recover: устройство просит недостающие
// версии GK (историю до своего входа) у участников (ADR-0010 §этап 1). Сервер находит
// устройства-помощники, у кого эти версии есть, и рассылает им recovery.gk_request.
// Аутентификация запроса — device JWT + членство (крипто-подпись запроса ключом
// личности — этап 3). Согласие в группе автоматическое: помощник и так делится с
// участником, имеющим право на историю.
func (s *Server) groupKeyRecover(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("groupID")
	id, _ := auth.FromContext(r.Context())

	role, err := s.Store.GroupRole(r.Context(), groupID, id.UserID)
	if err != nil || role == "" {
		writeErr(w, http.StatusForbidden, "not_member", "восстановление доступно только участникам группы")
		return
	}
	missing, err := s.Store.MissingGKVersions(r.Context(), groupID, id.DeviceID)
	if err != nil {
		log.Printf("groupKeyRecover: missing: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if len(missing) == 0 {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"requested": 0, "helpers": 0})
		return
	}
	helpers, err := s.Store.HelperDevices(r.Context(), groupID, id.DeviceID, missing)
	if err != nil {
		log.Printf("groupKeyRecover: helpers: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	encPub, err := s.Store.DeviceEncryptionPub(r.Context(), id.DeviceID)
	if err != nil {
		log.Printf("groupKeyRecover: enc pub: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	b64 := base64.RawURLEncoding
	versions := make([]int32, len(missing))
	copy(versions, missing)
	for _, helper := range helpers {
		s.notify(r.Context(), helper, "recovery.gk_request", map[string]any{
			"group_id":          groupID,
			"requester_device":  id.DeviceID,
			"requester_enc_pub": b64.EncodeToString(encPub),
			"versions":          versions,
		})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"requested": len(missing), "helpers": len(helpers)})
}

// groupKeyProvide — POST /groups/{groupID}/keys/recover/provide: помощник присылает
// обёртки GK под устройство-запросившее. Сервер кладёт их в group_wrapped_keys и
// уведомляет получателя recovery.gk_ready. Проверки: помощник — участник; получатель —
// устройство активного участника (ключи не уходят чужому).
func (s *Server) groupKeyProvide(w http.ResponseWriter, r *http.Request) {
	groupID := r.PathValue("groupID")
	id, _ := auth.FromContext(r.Context())

	role, err := s.Store.GroupRole(r.Context(), groupID, id.UserID)
	if err != nil || role == "" {
		writeErr(w, http.StatusForbidden, "not_member", "делиться ключами может только участник")
		return
	}
	var req struct {
		RequesterDevice string `json:"requester_device"`
		Keys            []struct {
			GKVersion          int32  `json:"gk_version"`
			SenderEphemeralPub string `json:"sender_ephemeral_pub"`
			Wrapped            string `json:"wrapped"`
		} `json:"keys"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4<<20)).Decode(&req); err != nil || req.RequesterDevice == "" || len(req.Keys) == 0 {
		writeErr(w, http.StatusBadRequest, "bad_json", "нужны requester_device и keys")
		return
	}
	member, err := s.Store.IsGroupMemberDevice(r.Context(), groupID, req.RequesterDevice)
	if err != nil {
		log.Printf("groupKeyProvide: member check: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if !member {
		writeErr(w, http.StatusBadRequest, "not_member_device", "получатель — не устройство активного участника")
		return
	}
	b64 := base64.RawURLEncoding
	keys := make([]store.RecoveryKey, 0, len(req.Keys))
	for _, k := range req.Keys {
		eph, err1 := b64.DecodeString(k.SenderEphemeralPub)
		wrapped, err2 := b64.DecodeString(k.Wrapped)
		if err1 != nil || err2 != nil || len(eph) != 32 || len(wrapped) < 24+16+32 || k.GKVersion <= 0 {
			writeErr(w, http.StatusBadRequest, "bad_key", "некорректная обёртка восстановления")
			return
		}
		keys = append(keys, store.RecoveryKey{GKVersion: k.GKVersion, SenderEphemeralPub: eph, Wrapped: wrapped})
	}
	if err := s.Store.SaveRecoveryKeys(r.Context(), groupID, req.RequesterDevice, keys); err != nil {
		log.Printf("groupKeyProvide: save: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	s.notify(r.Context(), req.RequesterDevice, "recovery.gk_ready", map[string]any{
		"group_id": groupID, "count": len(keys),
	})
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"saved": len(keys)})
}

// groupKeys — GET /groups/{groupID}/keys?since_version=: пропущенные wrapped_GK
// для устройства из токена (догон после офлайна / нового устройства).
// Членство не проверяется намеренно: выдаются только обёртки, адресованные
// этому устройству, — исключённый читает старые версии для истории
// (crypto-protocol §4.2: окно апелляции), новых версий у него нет.
func (s *Server) groupKeys(w http.ResponseWriter, r *http.Request) {
	var since int64
	if v := r.URL.Query().Get("since_version"); v != "" {
		since, _ = strconv.ParseInt(v, 10, 32)
	}
	groupID := r.PathValue("groupID")
	id, _ := auth.FromContext(r.Context())
	keys, err := s.Store.ListGroupKeysForDevice(r.Context(), groupID, id.DeviceID, int32(since))
	if err != nil {
		log.Printf("groupKeys: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// current_version — максимум по группе (может быть больше версий, выданных
	// этому устройству): новому устройству админа она нужна для ротации current+1.
	current, err := s.Store.CurrentGKVersion(r.Context(), groupID)
	if err != nil {
		log.Printf("groupKeys: current version: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	b64 := base64.RawURLEncoding
	type item struct {
		GKVersion          int32  `json:"gk_version"`
		SenderEphemeralPub string `json:"sender_ephemeral_pub"`
		Wrapped            string `json:"wrapped"`
	}
	out := make([]item, 0, len(keys))
	for _, k := range keys {
		out = append(out, item{k.GKVersion, b64.EncodeToString(k.SenderEphemeralPub), b64.EncodeToString(k.Wrapped)})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"keys": out, "current_version": current})
}
