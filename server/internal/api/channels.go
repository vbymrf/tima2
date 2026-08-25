// Публичные каналы (communities.md): вещание постов подписчикам. Контент публичный
// (не E2E) — осознанно для трансляции. Посты публикует владелец (MVP; авторы/премодерация —
// с who_can_post позже). Доставка новым постам — WS channel.post подписчикам.
//
// ── ПЕРВЫЙ REGISTRAR ────────────────────────────────────────────────────────
//
// Каналы вынесены первыми (программа архитектурных изменений, шаг 4): семь маршрутов,
// молодой код, минимум связей. Handler-ы здесь — свободные функции, а не методы
// *Server: им дают ровно то, чем они пользуются, и ничего больше.
//
// URL, коды ответов и тела не изменились ни в одном месте — существующие channels_test.go
// проходят без правки ожиданий, и это и есть проверка переноса.
package api

import (
	"context"
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

// ChannelStore — что каналам нужно от хранилища. Ровно десять методов из 132.
//
// Интерфейс объявлен ЗДЕСЬ, у потребителя, а не рядом со Store: так его сужение
// или расширение — правка этого файла, а не общего типа, который читают все.
// *store.Store реализует его без единого изменения, что и проверяет строка ниже.
type ChannelStore interface {
	CreateChannel(ctx context.Context, c store.Channel) (string, error)
	GetChannel(ctx context.Context, channelID string) (store.Channel, error)
	MyChannels(ctx context.Context, userID string) ([]store.ChannelView, error)
	DiscoverChannels(ctx context.Context, userID string, limit int) ([]store.ChannelView, error)
	Subscribe(ctx context.Context, channelID, userID string) error
	Unsubscribe(ctx context.Context, channelID, userID string) error
	IsSubscribed(ctx context.Context, channelID, userID string) (bool, error)
	SubscriberIDs(ctx context.Context, channelID string) ([]string, error)
	CreatePost(ctx context.Context, p store.ChannelPost) (uint64, error)
	ListPosts(ctx context.Context, channelID string, before uint64, limit int) ([]store.ChannelPost, error)
}

// Проверка соответствия — обязанность компилятора, а не прогона: разошлись
// сигнатуры — не собралось.
var _ ChannelStore = (*store.Store)(nil)

// RegisterChannels — все маршруты каналов одним вызовом.
func RegisterChannels(mux *http.ServeMux, st ChannelStore, n *Notifier, requireDevice Middleware) {
	mux.HandleFunc("POST /api/v1/channels", requireDevice(createChannel(st)))
	mux.HandleFunc("GET /api/v1/channels", requireDevice(listMyChannels(st)))
	mux.HandleFunc("GET /api/v1/channels/discover", requireDevice(discoverChannels(st)))
	mux.HandleFunc("POST /api/v1/channels/{channelID}/subscribe", requireDevice(subscribeChannel(st)))
	mux.HandleFunc("DELETE /api/v1/channels/{channelID}/subscribe", requireDevice(unsubscribeChannel(st)))
	mux.HandleFunc("POST /api/v1/channels/{channelID}/posts", requireDevice(postToChannel(st, n)))
	mux.HandleFunc("GET /api/v1/channels/{channelID}/posts", requireDevice(listChannelPosts(st)))
}

func createChannel(st ChannelStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
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
		channelID, err := st.CreateChannel(r.Context(), store.Channel{
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
}

func channelJSON(c store.ChannelView) map[string]any {
	return map[string]any{
		"channel_id": c.ChannelID, "title": c.Title, "description": c.Description,
		"owner_id": c.OwnerID, "is_public": c.IsPublic, "subscribed": c.Subscribed, "owner": c.Owner,
	}
}

func listMyChannels(st ChannelStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		channels, err := st.MyChannels(r.Context(), id.UserID)
		if err != nil {
			log.Printf("listMyChannels: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		writeChannels(w, channels)
	}
}

// discoverChannels — GET /channels/discover: публичные каналы для подписки.
func discoverChannels(st ChannelStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		channels, err := st.DiscoverChannels(r.Context(), id.UserID, limit)
		if err != nil {
			log.Printf("discoverChannels: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		writeChannels(w, channels)
	}
}

func writeChannels(w http.ResponseWriter, channels []store.ChannelView) {
	out := make([]map[string]any, 0, len(channels))
	for _, c := range channels {
		out = append(out, channelJSON(c))
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"channels": out})
}

func subscribeChannel(st ChannelStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		channelID := r.PathValue("channelID")
		if _, err := st.GetChannel(r.Context(), channelID); errors.Is(err, store.ErrChannelNotFound) {
			writeErr(w, http.StatusNotFound, "not_found", "канал не найден")
			return
		} else if err != nil {
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		id, _ := auth.FromContext(r.Context())
		if err := st.Subscribe(r.Context(), channelID, id.UserID); err != nil {
			log.Printf("subscribeChannel: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"subscribed": true})
	}
}

func unsubscribeChannel(st ChannelStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		if err := st.Unsubscribe(r.Context(), r.PathValue("channelID"), id.UserID); err != nil {
			log.Printf("unsubscribeChannel: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"subscribed": false})
	}
}

// postToChannel — POST /channels/{id}/posts: только владелец (MVP).
func postToChannel(st ChannelStore, n *Notifier) http.HandlerFunc {
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
			writeErr(w, http.StatusForbidden, "not_owner", "публиковать может только владелец канала")
			return
		}
		var req struct {
			Text string `json:"text"`
			// Nodes/Markup — ADR-0011 §4: публичный контур, узлы и разметка открытым
			// текстом. Пусто — клиент старого формата: заворачиваем text в один узел,
			// тот же переходный путь, что и у личных сообщений.
			Nodes  []string `json:"nodes,omitempty"`
			Markup string   `json:"markup,omitempty"` // компактный JSON (ADR-0011 §6); пусто = без разметки
		}
		if err := json.NewDecoder(io.LimitReader(r.Body, 256<<10)).Decode(&req); err != nil || req.Text == "" {
			writeErr(w, http.StatusBadRequest, "bad_text", "нужен непустой text")
			return
		}
		nodes := req.Nodes
		if len(nodes) == 0 {
			nodes = []string{req.Text}
		}
		var markup []byte
		if req.Markup != "" {
			if !json.Valid([]byte(req.Markup)) {
				writeErr(w, http.StatusBadRequest, "bad_markup", "markup должен быть валидным JSON")
				return
			}
			markup = []byte(req.Markup)
		}
		now := time.Now().UnixMilli()
		postID, err := st.CreatePost(r.Context(), store.ChannelPost{
			ChannelID: channelID, AuthorID: id.UserID, Text: req.Text,
			Nodes: nodes, Markup: markup, MarkupVersion: 1, CreatedAtUnixMs: now,
		})
		if err != nil {
			log.Printf("postToChannel: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		// Fan-out: событие channel.post устройствам всех подписчиков. Порядок «сначала
		// в журнал, потом live» держит Notifier — здесь про него знать не нужно.
		post := map[string]any{
			"channel_id": channelID, "post_id": postID, "author_id": id.UserID,
			"text": req.Text, "nodes": nodes, "created_at_unix_ms": now,
		}
		if len(markup) > 0 {
			post["markup"] = json.RawMessage(markup)
		}
		if subs, err := st.SubscriberIDs(r.Context(), channelID); err == nil {
			n.Users(r.Context(), subs, "channel.post", post)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusCreated)
		_ = json.NewEncoder(w).Encode(map[string]any{"post_id": postID})
	}
}

// listChannelPosts — GET /channels/{id}/posts: лента. Публичный канал читают все;
// приватный (позже) — только подписчики.
func listChannelPosts(st ChannelStore) http.HandlerFunc {
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
		if !ch.IsPublic {
			id, _ := auth.FromContext(r.Context())
			if sub, _ := st.IsSubscribed(r.Context(), channelID, id.UserID); !sub && ch.OwnerID != id.UserID {
				writeErr(w, http.StatusForbidden, "not_subscribed", "лента приватного канала — для подписчиков")
				return
			}
		}
		var before uint64
		if v := r.URL.Query().Get("before"); v != "" {
			before, _ = strconv.ParseUint(v, 10, 64)
		}
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		posts, err := st.ListPosts(r.Context(), channelID, before, limit)
		if err != nil {
			log.Printf("listChannelPosts: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		out := make([]map[string]any, 0, len(posts))
		for _, p := range posts {
			item := map[string]any{
				"post_id": p.PostID, "author_id": p.AuthorID, "text": p.Text,
				"nodes": p.Nodes, "created_at_unix_ms": p.CreatedAtUnixMs,
			}
			if len(p.Markup) > 0 {
				item["markup"] = json.RawMessage(p.Markup)
			}
			out = append(out, item)
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"channel": channelJSON(store.ChannelView{Channel: ch}), "posts": out})
	}
}
