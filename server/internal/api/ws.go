// WebSocket-доставка (websocket-events.md): один WS на устройство, auth — device JWT
// в первом кадре, дальше sync.pull/ack и live-поток событий из Redis Pub/Sub.
//
// auth → ok → sync.pull {cursor?, limit?} → события из device_events (те же
// event_id, что в live) → sync.done {next_cursor, more} → ack {event_id}
// сдвигает серверный cursor → live. Кадры — JSON (debug-транспорт по контракту;
// protobuf — вместе с клиентом). События идемпотентны: пересечение догона и
// live безопасно. sync.gap (cursor старше ретеншена) — вместе с GC worker-а.
package api

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"tima/server/internal/auth"
)

const (
	wsAuthTimeout  = 10 * time.Second
	wsPingInterval = 30 * time.Second // websocket-events.md: ping/pong каждые 30 с
)

func (s *Server) handleWS(w http.ResponseWriter, r *http.Request) {
	if s.Events == nil {
		writeErr(w, http.StatusServiceUnavailable, "no_events", "шина событий не сконфигурирована (REDIS_URL)")
		return
	}
	conn, err := websocket.Accept(w, r, nil)
	if err != nil {
		return // Accept сам ответил клиенту
	}
	defer conn.CloseNow()

	// Первый кадр — auth {token}
	authCtx, cancel := context.WithTimeout(r.Context(), wsAuthTimeout)
	var authFrame struct {
		Token string `json:"token"`
	}
	_, raw, err := conn.Read(authCtx)
	cancel()
	if err != nil || json.Unmarshal(raw, &authFrame) != nil {
		conn.Close(websocket.StatusPolicyViolation, "первый кадр — auth {token}")
		return
	}
	claims, err := s.Auth.Parse(authFrame.Token, auth.ScopeAccess)
	if err != nil {
		conn.Close(websocket.StatusPolicyViolation, "токен просрочен или подделан")
		return
	}
	deviceID := claims.DeviceID

	// Подписка ДО ok: после ok клиент вправе считать, что live-события не теряются
	sub, err := s.Events.Subscribe(r.Context(), deviceID)
	if err != nil {
		log.Printf("ws %s: subscribe: %v", deviceID, err)
		conn.Close(websocket.StatusInternalError, "шина событий недоступна")
		return
	}
	defer sub.Close()

	ctx := r.Context()
	if err := writeJSON(ctx, conn, map[string]any{"event": "ok", "device_id": deviceID}); err != nil {
		return
	}

	// Читатель клиентских кадров (sync.pull, ack); он же обрабатывает pong и close.
	// Пишет в conn только главный цикл — один конкурентный писатель.
	readErr := make(chan error, 1)
	frames := make(chan wsClientFrame, 8)
	go func() {
		for {
			_, raw, err := conn.Read(ctx)
			if err != nil {
				readErr <- err
				return
			}
			var f wsClientFrame
			if json.Unmarshal(raw, &f) != nil {
				continue // мусорный кадр не рвёт соединение
			}
			select {
			case frames <- f:
			case <-ctx.Done():
				return
			}
		}
	}()

	ping := time.NewTicker(wsPingInterval)
	defer ping.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-readErr:
			return // клиент закрылся
		case f := <-frames:
			if err := s.handleWSFrame(ctx, conn, deviceID, f); err != nil {
				return
			}
		case <-ping.C:
			pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
			err := conn.Ping(pingCtx)
			cancel()
			if err != nil {
				return
			}
		case msg, ok := <-sub.Frames():
			if !ok {
				conn.Close(websocket.StatusGoingAway, "шина событий закрылась")
				return
			}
			wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
			err := conn.Write(wctx, websocket.MessageText, []byte(msg.Payload))
			cancel()
			if err != nil {
				return
			}
		}
	}
}

// wsClientFrame — client→server кадры (websocket-events.md §Client → Server).
type wsClientFrame struct {
	Event   string `json:"event"`
	Cursor  *int64 `json:"cursor"`   // sync.pull; nil → серверная копия cursor
	Limit   int    `json:"limit"`    // sync.pull; 0 → 100, максимум 500
	EventID int64  `json:"event_id"` // ack
}

// handleWSFrame — sync.pull и ack; typing/receipt/presence — следующие итерации.
func (s *Server) handleWSFrame(ctx context.Context, conn *websocket.Conn, deviceID string, f wsClientFrame) error {
	switch f.Event {
	case "sync.pull":
		var cursor int64
		if f.Cursor != nil {
			cursor = *f.Cursor
		} else {
			var err error
			if cursor, err = s.Store.SyncCursor(ctx, deviceID); err != nil {
				log.Printf("ws %s: cursor: %v", deviceID, err)
				return writeJSON(ctx, conn, map[string]any{"event": "error", "code": "internal"})
			}
		}
		// Cursor старше ретеншена (GC удалил события после него) → sync.gap:
		// полный re-bootstrap REST-историей, дальше live с next_cursor.
		// cursor=0 — это и есть bootstrap, gap для него не нужен.
		if cursor > 0 {
			watermark, err := s.Store.GCWatermark(ctx)
			if err != nil {
				log.Printf("ws %s: watermark: %v", deviceID, err)
				return writeJSON(ctx, conn, map[string]any{"event": "error", "code": "internal"})
			}
			if cursor < watermark {
				next, err := s.Store.MaxDeviceEventID(ctx, deviceID)
				if err != nil {
					log.Printf("ws %s: max event: %v", deviceID, err)
					return writeJSON(ctx, conn, map[string]any{"event": "error", "code": "internal"})
				}
				if next < watermark {
					next = watermark
				}
				return writeJSON(ctx, conn, map[string]any{"event": "sync.gap", "next_cursor": next})
			}
		}
		events, err := s.Store.ListDeviceEvents(ctx, deviceID, cursor, f.Limit)
		if err != nil {
			log.Printf("ws %s: pull: %v", deviceID, err)
			return writeJSON(ctx, conn, map[string]any{"event": "error", "code": "internal"})
		}
		next := cursor
		for _, e := range events {
			frame := map[string]any{}
			if err := json.Unmarshal(e.Payload, &frame); err != nil {
				log.Printf("ws %s: событие %d повреждено: %v", deviceID, e.EventID, err)
				continue
			}
			frame["event"] = e.EventType
			frame["event_id"] = e.EventID
			if err := writeJSON(ctx, conn, frame); err != nil {
				return err
			}
			next = e.EventID
		}
		limit := f.Limit
		if limit <= 0 || limit > 500 {
			limit = 100
		}
		return writeJSON(ctx, conn, map[string]any{
			"event": "sync.done", "count": len(events), "next_cursor": next, "more": len(events) == limit,
		})
	case "ack":
		if f.EventID > 0 {
			if err := s.Store.SetSyncCursor(ctx, deviceID, f.EventID); err != nil {
				log.Printf("ws %s: ack: %v", deviceID, err)
			}
		}
		return nil
	default:
		return nil // неизвестные кадры молча пропускаем (typing и пр. — позже)
	}
}

func writeJSON(ctx context.Context, conn *websocket.Conn, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	return conn.Write(wctx, websocket.MessageText, raw)
}
