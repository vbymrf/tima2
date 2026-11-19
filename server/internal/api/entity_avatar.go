// Аватар сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ5): `PUT /{groups|channels|communities}/{id}/avatar {media_id}` —
// своё завершённое медиа, пусто — снять; ставят владелец и админ (у канала — владелец). Прочесть — `POST
// /entities/avatars {kind, ids}`: строкам Социума одним запросом.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// RegisterEntityAvatars — аватары групп, каналов, сообществ.
func RegisterEntityAvatars(mux *http.ServeMux, st *store.Store, requireDevice Middleware) {
	for _, k := range []struct{ kind, plural string }{{"group", "groups"}, {"channel", "channels"}, {"community", "communities"}} {
		mux.HandleFunc("PUT /api/v1/"+k.plural+"/{entityID}/avatar", requireDevice(setEntityAvatar(st, k.kind)))
	}
	mux.HandleFunc("POST /api/v1/entities/avatars", requireDevice(entityAvatars(st)))
}

// mayManageMain — «Основное» (§3): владелец и админ; у канала — владелец.
func mayManageMain(r *http.Request, st *store.Store, kind, id, userID string) (bool, error) {
	owner, err := entityOwner(r.Context(), st, kind, id)
	if err != nil {
		return false, err
	}
	if owner == userID {
		return true, nil
	}
	switch kind {
	case "group":
		role, err := st.GroupRole(r.Context(), id, userID)
		if errors.Is(err, store.ErrNotMember) {
			return false, nil
		}
		return roleRank[role] >= rankAdmin, err
	case "community":
		admins, err := st.CommunityAdmins(r.Context(), id)
		return listHas(admins, userID), err
	}
	return false, nil
}

func setEntityAvatar(st *store.Store, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			MediaID string `json:"media_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 1024)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		me, _ := auth.FromContext(r.Context())
		id := r.PathValue("entityID")
		ok, err := mayManageMain(r, st, kind, id, me.UserID)
		if entityMissing(err) {
			writeErr(w, http.StatusNotFound, "not_found", "не найдено")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		if !ok {
			writeErr(w, http.StatusForbidden, "forbidden", "аватар ставят владелец и админ")
			return
		}
		if err := store.SetEntityAvatar(r.Context(), st, kind, id, me.UserID, req.MediaID); errors.Is(err, store.ErrAvatarNotOwned) {
			writeErr(w, http.StatusForbidden, "not_owner", "аватаром может стать только своё завершённое медиа")
			return
		} else if err != nil {
			log.Printf("setEntityAvatar: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"avatar_media_id": req.MediaID})
	}
}

func entityAvatars(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Kind string   `json:"kind"`
			IDs  []string `json:"ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil || (req.Kind != "group" && req.Kind != "channel" && req.Kind != "community") || len(req.IDs) > 500 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны kind (group|channel|community) и ids до 500")
			return
		}
		out, err := store.EntityAvatars(r.Context(), st, req.Kind, req.IDs)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"avatars": out})
	}
}
