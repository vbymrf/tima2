// Публичные каналы (communities.md): вещание постов подписчикам. Контент публичный
// (не E2E) — осознанно для трансляции. Посты публикует владелец (MVP; авторы/премодерация —
// с who_can_post позже). Доставка новым постам — WS channel.post подписчикам.
package api

import (
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"strconv"
	"time"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

func (s *Server) createChannel(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Title       string `json:"title"`
		Description string `json:"description"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 64<<10)).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_json", "тело не парсится")
		return
	}
	if req.Title == "" || len(req.Title) > 200 {
		writeErr(w, http.StatusBadRequest, "bad_title", "title обязателен, до 200 байт")
		return
	}
	id, _ := auth.FromContext(r.Context())
	channelID, err := s.Store.CreateChannel(r.Context(), store.Channel{
		Title: req.Title, Description: req.Description, OwnerID: id.UserID, IsPublic: true,
	})
	if err != nil {
		log.Printf("createChannel: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"channel_id": channelID})
}

func channelJSON(c store.ChannelView) map[string]any {
	return map[string]any{
		"channel_id": c.ChannelID, "title": c.Title, "description": c.Description,
		"owner_id": c.OwnerID, "is_public": c.IsPublic, "subscribed": c.Subscribed, "owner": c.Owner,
	}
}

func (s *Server) listMyChannels(w http.ResponseWriter, r *http.Request) {
	id, _ := auth.FromContext(r.Context())
	channels, err := s.Store.MyChannels(r.Context(), id.UserID)
	if err != nil {
		log.Printf("listMyChannels: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	out := make([]map[string]any, 0, len(channels))
	for _, c := range channels {
		out = append(out, channelJSON(c))
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"channels": out})
}

// discoverChannels — GET /channels/discover: публичные каналы для подписки.
func (s *Server) discoverChannels(w http.ResponseWriter, r *http.Request) {
	id, _ := auth.FromContext(r.Context())
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	channels, err := s.Store.DiscoverChannels(r.Context(), id.UserID, limit)
	if err != nil {
		log.Printf("discoverChannels: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	out := make([]map[string]any, 0, len(channels))
	for _, c := range channels {
		out = append(out, channelJSON(c))
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"channels": out})
}

func (s *Server) subscribeChannel(w http.ResponseWriter, r *http.Request) {
	channelID := r.PathValue("channelID")
	if _, err := s.Store.GetChannel(r.Context(), channelID); errors.Is(err, store.ErrChannelNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "канал не найден")
		return
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	id, _ := auth.FromContext(r.Context())
	if err := s.Store.Subscribe(r.Context(), channelID, id.UserID); err != nil {
		log.Printf("subscribeChannel: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"subscribed": true})
}

func (s *Server) unsubscribeChannel(w http.ResponseWriter, r *http.Request) {
	id, _ := auth.FromContext(r.Context())
	if err := s.Store.Unsubscribe(r.Context(), r.PathValue("channelID"), id.UserID); err != nil {
		log.Printf("unsubscribeChannel: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"subscribed": false})
}

// postToChannel — POST /channels/{id}/posts: только владелец (MVP).
func (s *Server) postToChannel(w http.ResponseWriter, r *http.Request) {
	channelID := r.PathValue("channelID")
	ch, err := s.Store.GetChannel(r.Context(), channelID)
	if errors.Is(err, store.ErrChannelNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "канал не найден")
		return
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	id, _ := auth.FromContext(r.Context())
	if ch.OwnerID != id.UserID {
		writeErr(w, http.StatusForbidden, "not_owner", "публиковать может только владелец канала")
		return
	}
	var req struct {
		Text string `json:"text"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&req); err != nil || req.Text == "" {
		writeErr(w, http.StatusBadRequest, "bad_text", "нужен непустой text")
		return
	}
	now := time.Now().UnixMilli()
	postID, err := s.Store.CreatePost(r.Context(), store.ChannelPost{
		ChannelID: channelID, AuthorID: id.UserID, Text: req.Text, CreatedAtUnixMs: now,
	})
	if err != nil {
		log.Printf("postToChannel: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	// Fan-out: событие channel.post устройствам всех подписчиков
	post := map[string]any{
		"channel_id": channelID, "post_id": postID, "author_id": id.UserID,
		"text": req.Text, "created_at_unix_ms": now,
	}
	if subs, err := s.Store.SubscriberIDs(r.Context(), channelID); err == nil {
		for _, uid := range subs {
			if devices, err := s.Store.ListDevices(r.Context(), uid); err == nil {
				for _, d := range devices {
					s.notify(r.Context(), d.DeviceID, "channel.post", post)
				}
			}
		}
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]any{"post_id": postID})
}

// listChannelPosts — GET /channels/{id}/posts: лента. Публичный канал читают все;
// приватный (позже) — только подписчики.
func (s *Server) listChannelPosts(w http.ResponseWriter, r *http.Request) {
	channelID := r.PathValue("channelID")
	ch, err := s.Store.GetChannel(r.Context(), channelID)
	if errors.Is(err, store.ErrChannelNotFound) {
		writeErr(w, http.StatusNotFound, "not_found", "канал не найден")
		return
	} else if err != nil {
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	if !ch.IsPublic {
		id, _ := auth.FromContext(r.Context())
		if sub, _ := s.Store.IsSubscribed(r.Context(), channelID, id.UserID); !sub && ch.OwnerID != id.UserID {
			writeErr(w, http.StatusForbidden, "not_subscribed", "лента приватного канала — для подписчиков")
			return
		}
	}
	var before uint64
	if v := r.URL.Query().Get("before"); v != "" {
		before, _ = strconv.ParseUint(v, 10, 64)
	}
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	posts, err := s.Store.ListPosts(r.Context(), channelID, before, limit)
	if err != nil {
		log.Printf("listChannelPosts: %v", err)
		writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
		return
	}
	out := make([]map[string]any, 0, len(posts))
	for _, p := range posts {
		out = append(out, map[string]any{
			"post_id": p.PostID, "author_id": p.AuthorID, "text": p.Text,
			"created_at_unix_ms": p.CreatedAtUnixMs,
		})
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"channel": channelJSON(store.ChannelView{Channel: ch}), "posts": out})
}
