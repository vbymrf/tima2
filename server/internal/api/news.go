package api

import (
	"context"
	"encoding/json"
	"log"
	"net/http"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// Новое для аккаунта, который сейчас не открыт (заказчик 2026-10-07, панель переходов: «оранжевым —
// количество полученных»).
//
// Устройство держит открытым один аккаунт; остальным сообщения копятся в журнале событий этого
// устройства, и устройство их не забирает. Сколько там лежит — сервер знает без содержимого: это
// события `message.new` и `message.group` после последнего подтверждённого (`ack`) номера.
// Своё не считается — копии собственных сообщений с других устройств аккаунта новым не являются.
//
// Ручка зовётся входом того самого аккаунта, о котором спрашивают: чужое число не узнать.
// Хранилищу новый метод не нужен — это ровно то, что отдаёт `sync.pull` после курсора.

// NewsStore — что ручке нужно от хранилища.
type NewsStore interface {
	SyncCursor(ctx context.Context, deviceID string) (int64, error)
	ListDeviceEvents(ctx context.Context, deviceID string, after int64, limit int) ([]store.DeviceEvent, error)
}

var _ NewsStore = (*store.Store)(nil)

// newsPages — сколько страниц журнала просматривать; дальше число всё равно «99+».
const (
	newsPage  = 500
	newsPages = 2
)

// RegisterNews — одна ручка.
func RegisterNews(mux *http.ServeMux, st NewsStore, requireDevice Middleware) {
	mux.HandleFunc("GET /api/v1/users/me/news", requireDevice(news(st)))
}

// news — GET /users/me/news → {"messages": N, "more": bool}. `more` — просмотрено не всё.
func news(st NewsStore) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, _ := auth.FromContext(r.Context())
		after, err := st.SyncCursor(r.Context(), id.DeviceID)
		if err != nil {
			log.Printf("news: cursor: %v", err)
			writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
			return
		}
		count, more := 0, false
		for page := 0; page < newsPages; page++ {
			events, err := st.ListDeviceEvents(r.Context(), id.DeviceID, after, newsPage)
			if err != nil {
				log.Printf("news: events: %v", err)
				writeErr(w, http.StatusInternalServerError, "internal", "ошибка хранилища")
				return
			}
			count += countNews(events, id.UserID)
			if len(events) < newsPage {
				more = false
				break
			}
			after = events[len(events)-1].EventID
			more = true
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"messages": count, "more": more})
	}
}

// countNews — сколько среди событий новых сообщений не от самого аккаунта. Событие без
// `sender_id` (записанное до 2026-10-07) считается: отличить его нечем, а потерять новое хуже.
func countNews(events []store.DeviceEvent, userID string) int {
	n := 0
	for _, e := range events {
		if e.EventType != "message.new" && e.EventType != "message.group" {
			continue
		}
		var head struct {
			SenderID string `json:"sender_id"`
		}
		_ = json.Unmarshal(e.Payload, &head)
		if head.SenderID != "" && head.SenderID == userID {
			continue
		}
		n++
	}
	return n
}
