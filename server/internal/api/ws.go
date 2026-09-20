// WebSocket-доставка (websocket-events.md): один WS на устройство, auth — device JWT
// в первом кадре, дальше sync.pull/ack и live-поток событий из Redis Pub/Sub.
//
// auth → ok → sync.pull {cursor?, limit?} → события из device_events (те же
// event_id, что в live) → sync.done {next_cursor, more} → ack {event_id}
// сдвигает серверный cursor → live. Кадры — JSON (debug-транспорт по контракту;
// protobuf — вместе с клиентом). События идемпотентны: пересечение догона и
// live безопасно. sync.gap (cursor старше ретеншена) — вместе с GC worker-а.
//
// **Гарантию доставки даёт не шина, а журнал.** Redis Pub/Sub — «не более одного
// раза»: нет подписчика, переполнился буфер, дёрнулось соединение — кадр исчез, и
// никто не узнал. Так 2026-09-20 потерялся call.incoming, и человек остался без
// звонка при живом соединении: событие лежало в device_events, а перечитать его
// было некому — клиент читает журнал только при переподключении.
//
// Поэтому соединение помнит, что оно уже отдало (sent), и раз в wsCatchupInterval
// смотрит в журнал после этого места. Нашлось — это ровно те кадры, которые шина
// потеряла: они уходят той же дорогой и с тем же event_id. Повтор безвреден —
// клиент отбирает по event_id (EventStreamProtocol.Decision.Seen).
package api

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"time"

	"github.com/coder/websocket"

	"tima/server/internal/auth"
	"tima/server/internal/store"
)

// Сроки рассчитаны на мобильную сеть. Прежние значения (5 с на pong, 10 с на запись)
// молча рвали соединение у клиента в 4G: под нагрузкой оператора оборот пакета
// доходит до нескольких секунд, и сервер закрывал вполне живого собеседника. Клиент
// переподключался, снова не укладывался — и так по кругу.
const (
	wsAuthTimeout  = 20 * time.Second
	wsPingInterval = 30 * time.Second // websocket-events.md: ping/pong каждые 30 с
	wsPongTimeout  = 20 * time.Second // ответ на ping; меньше интервала, иначе очередь ping-ов
	wsWriteTimeout = 30 * time.Second // отдача кадра клиенту

	// Как часто соединение сверяется с журналом. Пять секунд — не про нагрузку (один
	// запрос по индексу на устройство), а про звонок: дольше — и досланный вызов
	// придёт, когда звонящий уже положил трубку.
	wsCatchupInterval = 5 * time.Second
	wsCatchupLimit    = 100
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
	catchup := time.NewTicker(wsCatchupInterval)
	defer catchup.Stop()

	// Докуда это соединение отдало журнал. Двигают оба пути — и sync.pull, и live;
	// гонки нет: пишет в сокет только этот цикл.
	sent := int64(0)
	// До первого sync.pull дожимать нечего: откуда клиент читает, ещё не сказано, и
	// «после нуля» означало бы вывалить ему всю историю.
	pulled := false
	for {
		select {
		case <-ctx.Done():
			return
		case <-readErr:
			return // клиент закрылся
		case f := <-frames:
			if f.Event == "sync.pull" {
				pulled = true
			}
			if err := s.handleWSFrame(ctx, conn, deviceID, f, &sent); err != nil {
				return
			}
		case <-ping.C:
			pingCtx, cancel := context.WithTimeout(ctx, wsPongTimeout)
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
			// Кадр мог обогнать шину: догон или дожим уже отдал его. Второй раз
			// клиент его отбросит, но платить за это трафиком незачем.
			var live struct {
				EventID int64 `json:"event_id"`
			}
			if json.Unmarshal([]byte(msg.Payload), &live) == nil && live.EventID > 0 {
				if live.EventID <= sent {
					continue
				}
				sent = live.EventID
			}
			wctx, cancel := context.WithTimeout(ctx, wsWriteTimeout)
			err := conn.Write(wctx, websocket.MessageText, []byte(msg.Payload))
			cancel()
			if err != nil {
				return
			}

		case <-catchup.C:
			if !pulled {
				continue
			}
			missed, err := s.Store.ListDeviceEvents(ctx, deviceID, sent, wsCatchupLimit)
			if err != nil {
				log.Printf("ws %s: сверка с журналом: %v", deviceID, err)
				continue
			}
			if len(missed) == 0 {
				continue
			}
			// Это число — прямая мера потерь шины, а не косвенная. Молчит журнал —
			// Pub/Sub ничего не терял; говорит — знаем, сколько и кому.
			log.Printf("ws %s: шина потеряла событий: %d (после %d) — досылаю", deviceID, len(missed), sent)
			if err := sendStored(ctx, conn, deviceID, missed, &sent); err != nil {
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
// sent — докуда соединение отдало журнал; sync.pull его двигает.
func (s *Server) handleWSFrame(ctx context.Context, conn *websocket.Conn, deviceID string, f wsClientFrame, sent *int64) error {
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
				// Дожим обязан начинаться оттуда же: до next_cursor журнал пуст,
				// и сверка иначе нашла бы там «потери», которых нет.
				if next > *sent {
					*sent = next
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
		if err := sendStored(ctx, conn, deviceID, events, &next); err != nil {
			return err
		}
		if next > *sent {
			*sent = next
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

// sendStored отдаёт события журнала кадрами и двигает last на последнее отданное.
// Одна дорога и для догона (sync.pull), и для дожима: разойдись они — кадр одного и
// того же события приходил бы клиенту в двух видах.
//
// Обычной функцией, а не методом *Server: полей сервера ей не нужно ни одного, а
// каждый метод общего типа — это ещё один handler, который видит всё
// (architecture_test.go, бюджет методов *Server).
func sendStored(ctx context.Context, conn *websocket.Conn, deviceID string, events []store.DeviceEvent, last *int64) error {
	for _, e := range events {
		frame := map[string]any{}
		if err := json.Unmarshal(e.Payload, &frame); err != nil {
			// Отдать нечего, но отметку двигаем: иначе испорченное событие
			// перечитывается вечно — и дожимом теперь каждые пять секунд.
			log.Printf("ws %s: событие %d повреждено: %v", deviceID, e.EventID, err)
			*last = e.EventID
			continue
		}
		frame["event"] = e.EventType
		frame["event_id"] = e.EventID
		if err := writeJSON(ctx, conn, frame); err != nil {
			return err
		}
		*last = e.EventID
	}
	return nil
}

func writeJSON(ctx context.Context, conn *websocket.Conn, v any) error {
	raw, err := json.Marshal(v)
	if err != nil {
		return err
	}
	wctx, cancel := context.WithTimeout(ctx, wsWriteTimeout)
	defer cancel()
	return conn.Write(wctx, websocket.MessageText, raw)
}
