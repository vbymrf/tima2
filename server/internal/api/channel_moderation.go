// Выключатели обсуждения, удаление записей и модераторы канала
// (ПЛАН-КАНАЛОВ К3, ADR-0024 §6).
//
// ── ЧТО ЗДЕСЬ ЕСТЬ И ЧЕГО НЕТ ───────────────────────────────────────────────
//
// Модерации у каналов не было вовсе (МОДЕРАЦИЯ.md §2). Здесь заведена ровно та её часть,
// без которой не выполняется ответ заказчика 2026-09-08: «владелец, модератор канала может
// удалить любое сообщение своего канала; автор — только своё». Роль модератора, удаление
// и два выключателя обсуждения — всё.
//
// Жалоб, премодерации, медленного режима и блокировки участника здесь нет: это отдельная
// работа, и смешивать её с комментариями значит не закончить ни ту, ни другую.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// ModerationStore — что модерации каналов нужно от хранилища.
type ModerationStore interface {
	GetChannel(ctx context.Context, channelID string) (store.Channel, error)
	SetChannelComments(ctx context.Context, channelID, ownerID string, enabled bool) error
	SetPostComments(ctx context.Context, channelID string, postID uint64, actorID string, closed bool) error
	DeleteChannelPost(ctx context.Context, channelID string, postID uint64, actorID string) error
	SetChannelModerator(ctx context.Context, channelID, ownerID, userID string, on bool) error
	ChannelModerators(ctx context.Context, channelID string) ([]string, error)
}

var _ ModerationStore = (*store.Store)(nil)

// RegisterChannelModeration — выключатели, удаление и модераторы.
func RegisterChannelModeration(mux *http.ServeMux, st ModerationStore, requireDevice Middleware) {
	mux.HandleFunc("PUT /api/v1/channels/{channelID}/comments", requireDevice(setChannelComments(st)))
	mux.HandleFunc("PUT /api/v1/channels/{channelID}/posts/{postID}/comments", requireDevice(setPostComments(st)))
	mux.HandleFunc("DELETE /api/v1/channels/{channelID}/posts/{postID}", requireDevice(deleteChannelPost(st)))
	mux.HandleFunc("GET /api/v1/channels/{channelID}/moderators", requireDevice(listModerators(st)))
	mux.HandleFunc("POST /api/v1/channels/{channelID}/moderators", requireDevice(addModerator(st)))
	mux.HandleFunc("DELETE /api/v1/channels/{channelID}/moderators/{userID}", requireDevice(removeModerator(st)))
}

// answerModeration — общий разбор ответа хранилища.
//
// «Не разрешено» отдаётся как 403, а не как 404: в отличие от чтения, здесь человек уже
// знает, что запись существует, — он на неё смотрит. Прятать от него причину отказа значит
// оставить его нажимать кнопку ещё раз.
func answerModeration(w http.ResponseWriter, err error, what string) bool {
	switch {
	case errors.Is(err, store.ErrNotAllowed):
		writeErr(w, http.StatusForbidden, "not_allowed", "это может владелец канала или модератор")
		return false
	case errors.Is(err, store.ErrChannelNotFound):
		writeErr(w, http.StatusNotFound, "not_found", "канал или запись не найдены")
		return false
	case err != nil:
		log.Printf("%s: %v", what, err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return false
	}
	return true
}

func setChannelComments(st ModerationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Enabled *bool `json:"enabled"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Enabled == nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен enabled")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if !answerModeration(w, st.SetChannelComments(r.Context(), r.PathValue("channelID"), id.UserID, *req.Enabled), "setChannelComments") {
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"comments_enabled": *req.Enabled})
	}
}

func setPostComments(st ModerationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		postID, err := strconv.ParseUint(r.PathValue("postID"), 10, 64)
		if err != nil || postID == 0 {
			writeErr(w, http.StatusBadRequest, "bad_post_id", "post_id — целое число")
			return
		}
		var req struct {
			Closed *bool `json:"closed"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.Closed == nil {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен closed")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if !answerModeration(w, st.SetPostComments(r.Context(), r.PathValue("channelID"), postID, id.UserID, *req.Closed), "setPostComments") {
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"comments_closed": *req.Closed})
	}
}

// deleteChannelPost — убрать запись или комментарий.
//
// Один маршрут на то и другое, потому что это одно и то же: комментарий — запись с
// заполненным корнем. Право различается не видом строки, а тем, чья она.
func deleteChannelPost(st ModerationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		postID, err := strconv.ParseUint(r.PathValue("postID"), 10, 64)
		if err != nil || postID == 0 {
			writeErr(w, http.StatusBadRequest, "bad_post_id", "post_id — целое число")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if !answerModeration(w, st.DeleteChannelPost(r.Context(), r.PathValue("channelID"), postID, id.UserID), "deleteChannelPost") {
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

func listModerators(st ModerationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ids, err := st.ChannelModerators(r.Context(), r.PathValue("channelID"))
		if !answerModeration(w, err, "listModerators") {
			return
		}
		if ids == nil {
			ids = []string{}
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"moderators": ids})
	}
}

func addModerator(st ModerationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			UserID string `json:"user_id"`
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 4<<10)).Decode(&req); err != nil || req.UserID == "" {
			writeErr(w, http.StatusBadRequest, "bad_json", "нужен user_id")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if !answerModeration(w, st.SetChannelModerator(r.Context(), r.PathValue("channelID"), id.UserID, req.UserID, true), "addModerator") {
			return
		}
		w.WriteHeader(http.StatusCreated)
	}
}

func removeModerator(st ModerationStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		if !answerModeration(w, st.SetChannelModerator(r.Context(), r.PathValue("channelID"), id.UserID, r.PathValue("userID"), false), "removeModerator") {
			return
		}
		w.WriteHeader(http.StatusNoContent)
	}
}
