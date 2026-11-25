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
	"strings"
	"unicode/utf8"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// RegisterEntityAvatars — аватары групп, каналов, сообществ.
func RegisterEntityAvatars(mux *http.ServeMux, st *store.Store, requireDevice Middleware) {
	for _, k := range []struct{ kind, plural string }{{"group", "groups"}, {"channel", "channels"}, {"community", "communities"}} {
		mux.HandleFunc("PUT /api/v1/"+k.plural+"/{entityID}/avatar", requireDevice(setEntityAvatar(st, k.kind)))
		// Название и описание (ПЛАН-(ВС) 10–11, 2026-11-20): «Управление → Основное», те же права, что у аватара.
		mux.HandleFunc("PUT /api/v1/"+k.plural+"/{entityID}/about", requireDevice(setEntityAbout(st, k.kind)))
	}
	// Описания строкам каталога одним запросом — открытые, как названия (решение 10).
	mux.HandleFunc("POST /api/v1/entities/about", requireDevice(entityAbouts(st)))
	mux.HandleFunc("POST /api/v1/entities/avatars", requireDevice(entityAvatars(st)))
	// Число участников и подписчиков (§4б п. 9) — тем же видом запроса.
	mux.HandleFunc("POST /api/v1/entities/counts", requireDevice(entityCounts(st)))
}

// mayManageMain — «Основное» (§3): владелец и админ — у всех трёх видов (у канала админ — с СЦ21).
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
	case "channel":
		role, err := store.ChannelRole(r.Context(), st, id, userID)
		return role == "admin", err
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

// entityCounts — POST /entities/counts {kind, ids}: номер → число участников (группа) или подписчиков.
func entityCounts(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Kind string   `json:"kind"`
			IDs  []string `json:"ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil || (req.Kind != "group" && req.Kind != "channel" && req.Kind != "community") || len(req.IDs) > 500 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны kind (group|channel|community) и ids до 500")
			return
		}
		out, err := store.EntityCounts(r.Context(), st, req.Kind, req.IDs)
		if err != nil {
			log.Printf("entityCounts: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"counts": out})
	}
}

// setEntityAbout — PUT /{groups|channels|communities}/{id}/about {title?, about?}: название и описание. Поля
// необязательны: нет поля — не меняется; пустое описание — описания нет. Правят владелец и админ, у канала — владелец.
func setEntityAbout(st *store.Store, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Title *string `json:"title"`
			About *string `json:"about"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
			return
		}
		if req.Title != nil {
			t := strings.TrimSpace(*req.Title)
			if t == "" || len(t) > 200 {
				writeErr(w, http.StatusBadRequest, "bad_title", "название непустое, до 200 байт")
				return
			}
			req.Title = &t
		}
		if req.About != nil {
			a := strings.TrimSpace(*req.About)
			if utf8.RuneCountInString(a) > store.MaxAboutRunes {
				writeErr(w, http.StatusBadRequest, "bad_about", "описание до 4096 знаков")
				return
			}
			req.About = &a
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
			writeErr(w, http.StatusForbidden, "forbidden", "название и описание правят владелец и админ")
			return
		}
		if err := store.SetEntityAbout(r.Context(), st, kind, id, req.Title, req.About); errors.Is(err, store.ErrEntityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "не найдено")
			return
		} else if err != nil {
			log.Printf("setEntityAbout: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := map[string]any{}
		if req.Title != nil {
			out["title"] = *req.Title
		}
		if req.About != nil {
			out["about"] = *req.About
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(out)
	}
}

// entityAbouts — POST /entities/about {kind, ids}: номер → описание; у кого описания нет — нет в ответе.
func entityAbouts(st *store.Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Kind string   `json:"kind"`
			IDs  []string `json:"ids"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil || (req.Kind != "group" && req.Kind != "channel" && req.Kind != "community") || len(req.IDs) > 500 {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужны kind (group|channel|community) и ids до 500")
			return
		}
		out, err := store.EntityAbouts(r.Context(), st, req.Kind, req.IDs)
		if err != nil {
			log.Printf("entityAbouts: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Названия несекретных — рядом (2026-11-25, поле добавлено, прежние ответы не меняются).
		titles, err := store.EntityTitles(r.Context(), st, req.Kind, req.IDs)
		if err != nil {
			log.Printf("entityAbouts: названия: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"about": out, "titles": titles})
	}
}
