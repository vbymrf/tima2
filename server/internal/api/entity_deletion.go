// Удаление сущности с месяцем на отмену (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ14).
//
// Удаляет и отменяет владелец — в том числе после административной смены личности (§1.3, 5; §6.3): распоряжаться
// он может, передать — нет. Группа удаляется прежним `DELETE /groups/{id}` (ответ тот же, но ставится срок); у канала
// и сообщества удаление новое. Отмена — `DELETE …/{id}/deletion`, пока срок не прошёл.
package api

import (
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// entityDeletionDelay — месяц на отмену, как у удаления личности (Р37, Р39).
const entityDeletionDelay = 30 * 24 * time.Hour

// RegisterDeletion — удалить канал и сообщество; отменить удаление группы, канала, сообщества.
func RegisterDeletion(mux *http.ServeMux, st OwnershipEntities, del store.Deletions, requireDevice Middleware) {
	mux.HandleFunc("DELETE /api/v1/channels/{entityID}", requireDevice(scheduleDeletion(st, del, "channel")))
	mux.HandleFunc("DELETE /api/v1/communities/{entityID}", requireDevice(scheduleDeletion(st, del, "community")))
	for _, k := range []struct{ kind, plural string }{{"group", "groups"}, {"channel", "channels"}, {"community", "communities"}} {
		mux.HandleFunc("GET /api/v1/"+k.plural+"/{entityID}/deletion", requireDevice(deletionState(st, del, k.kind)))
		mux.HandleFunc("DELETE /api/v1/"+k.plural+"/{entityID}/deletion", requireDevice(cancelDeletion(st, del, k.kind)))
	}
}

func ownerOnly(w http.ResponseWriter, r *http.Request, st OwnershipEntities, kind string) (string, bool) {
	id, _ := auth.FromContext(r.Context())
	entityID := r.PathValue("entityID")
	owner, err := entityOwner(r.Context(), st, kind, entityID)
	if entityMissing(err) {
		writeErr(w, http.StatusNotFound, "not_found", "не найдено")
		return "", false
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return "", false
	}
	if owner != id.UserID {
		writeErr(w, http.StatusForbidden, "forbidden", "удаляет и отменяет только владелец")
		return "", false
	}
	return entityID, true
}

func scheduleDeletion(st OwnershipEntities, del store.Deletions, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		entityID, ok := ownerOnly(w, r, st, kind)
		if !ok {
			return
		}
		if _, err := del.Schedule(r.Context(), kind, entityID, entityDeletionDelay); err != nil {
			log.Printf("scheduleDeletion %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// deletionState — GET …/deletion: назначено ли и когда (виден участникам: полоса «Будет удалена …»).
func deletionState(st OwnershipEntities, del store.Deletions, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		entityID := r.PathValue("entityID")
		if _, err := entityOwner(r.Context(), st, kind, entityID); entityMissing(err) {
			writeErr(w, http.StatusNotFound, "not_found", "не найдено")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		at, err := del.DeleteAt(r.Context(), kind, entityID)
		if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		answer := map[string]any{"scheduled": at != nil}
		if at != nil {
			answer["delete_at"] = at.UTC().Format(time.RFC3339)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(answer)
	}
}

func cancelDeletion(st OwnershipEntities, del store.Deletions, kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		entityID, ok := ownerOnly(w, r, st, kind)
		if !ok {
			return
		}
		if err := del.Cancel(r.Context(), kind, entityID); errors.Is(err, store.ErrNotScheduled) {
			writeErr(w, http.StatusConflict, "not_scheduled", "удаление не назначено или срок прошёл")
			return
		} else if err != nil {
			log.Printf("cancelDeletion %s: %v", kind, err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
