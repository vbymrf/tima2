// «Секретная» у канала и сообщества (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2). У группы признак меняется тем же
// PATCH, что и прочие её настройки; у канала и сообщества такого PATCH нет — отдельная ручка.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// SecretStore — что нужно ручкам «секретная».
type SecretStore interface {
	GetChannel(ctx context.Context, channelID string) (store.Channel, error)
	SetChannelHidden(ctx context.Context, channelID string, hidden bool) error
	GetCommunity(ctx context.Context, communityID string) (store.Community, error)
	CommunityAdmins(ctx context.Context, communityID string) ([]string, error)
	SetCommunityHidden(ctx context.Context, communityID string, hidden bool) error
}

var _ SecretStore = (*store.Store)(nil)

// RegisterSecret — PUT …/hidden {hidden} у канала (владелец) и сообщества (владелец и админы).
func RegisterSecret(mux *http.ServeMux, st SecretStore, requireDevice Middleware) {
	mux.HandleFunc("PUT /api/v1/channels/{channelID}/hidden", requireDevice(setChannelHidden(st)))
	mux.HandleFunc("PUT /api/v1/communities/{communityID}/hidden", requireDevice(setCommunityHidden(st)))
}

func hiddenRequest(w http.ResponseWriter, r *http.Request) (bool, bool) {
	var req struct {
		Hidden *bool `json:"hidden"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 1<<10)).Decode(&req); err != nil || req.Hidden == nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "нужен hidden: true|false")
		return false, false
	}
	return *req.Hidden, true
}

func setChannelHidden(st SecretStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		channelID := r.PathValue("channelID")
		ch, err := st.GetChannel(r.Context(), channelID)
		if errors.Is(err, store.ErrChannelNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "канал не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if ch.OwnerID != id.UserID {
			writeErr(w, http.StatusForbidden, "forbidden", "«секретный» ставит владелец канала")
			return
		}
		hidden, ok := hiddenRequest(w, r)
		if !ok {
			return
		}
		if err := st.SetChannelHidden(r.Context(), channelID, hidden); err != nil {
			log.Printf("setChannelHidden: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"hidden": hidden})
	}
}

func setCommunityHidden(st SecretStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		communityID := r.PathValue("communityID")
		c, err := st.GetCommunity(r.Context(), communityID)
		if errors.Is(err, store.ErrCommunityNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "сообщество не найдено")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		allowed := c.OwnerID == id.UserID
		if !allowed {
			admins, _ := st.CommunityAdmins(r.Context(), communityID)
			for _, a := range admins {
				allowed = allowed || a == id.UserID
			}
		}
		if !allowed {
			writeErr(w, http.StatusForbidden, "forbidden", "«секретное» ставят владелец и админы")
			return
		}
		hidden, ok := hiddenRequest(w, r)
		if !ok {
			return
		}
		if err := st.SetCommunityHidden(r.Context(), communityID, hidden); err != nil {
			log.Printf("setCommunityHidden: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"hidden": hidden})
	}
}
