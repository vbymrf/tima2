// API групповых ключей (api-overview.md §Группы; crypto-protocol.md §4).
// Серверная сторона клиентского GroupKeyManager: приём ротации GK и выдача
// пропущенных wrapped_GK. Сервер сами GK не видит — только escrow и обёртки.
//
// TODO(Group Service): проверка членства/роли ротирующего появится вместе с
// модулем групп (membership) — до него любой аутентифицированный клиент может
// ротировать любой group_id; для закрытого dev-контура это приемлемо.
package api

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"

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
	// key.rotated онлайн-устройствам участников (websocket-events.md)
	if s.Events != nil {
		for recipient, w := range wrapped {
			if err := s.Events.Publish(r.Context(), recipient, "key.rotated", map[string]any{
				"group_id":             r.PathValue("groupID"),
				"gk_version":           req.GKVersion,
				"sender_ephemeral_pub": req.SenderEphemeralPub,
				"wrapped_gk":           base64.RawURLEncoding.EncodeToString(w),
			}); err != nil {
				log.Printf("groupRotate: publish %s: %v", recipient, err)
			}
		}
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"group_id": r.PathValue("groupID"), "gk_version": req.GKVersion})
}

// groupKeys — GET /groups/{groupID}/keys?since_version=: пропущенные wrapped_GK
// для устройства из токена (догон после офлайна / нового устройства).
func (s *Server) groupKeys(w http.ResponseWriter, r *http.Request) {
	var since int64
	if v := r.URL.Query().Get("since_version"); v != "" {
		since, _ = strconv.ParseInt(v, 10, 32)
	}
	id, _ := auth.FromContext(r.Context())
	keys, err := s.Store.ListGroupKeysForDevice(r.Context(), r.PathValue("groupID"), id.DeviceID, int32(since))
	if err != nil {
		log.Printf("groupKeys: %v", err)
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
	_ = json.NewEncoder(w).Encode(map[string]any{"keys": out})
}
