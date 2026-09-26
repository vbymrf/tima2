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
//
// ── ПО ШИНЕ ЕДЕТ ПОДСКАЗКА, А НЕ ТЕЛО (П4) ──────────────────────────────────
//
// `sync.poke {event_id, pts, qts, seq}` — «есть что забрать». Клиент сверяет номер со
// своим курсором и сам зовёт sync.pull; вершины трёх полос при этом говорят, что
// именно потерялось и где. `call.poke {cts}` — «в ленте звонков есть до №cts» (ВЗ0а);
// `call.poke {call_id}` остался только у групповых звонков, которые идут журналом.
//
// Подсказка **протухнуть не может**, потому что не несёт состояния. Вызов недельной
// давности, доехавший до телефона, приводит не к звонку, а к запросу, который честно
// отвечает «кончился».
//
// Дожим при этом остаётся страховкой на потерянную подсказку и потому стал реже:
// разрыв теперь ловит номер, а не таймер.
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

	// Как часто соединение сверяется с журналом.
	//
	// **Было пять секунд, стало тридцать — и это не экономия** (П6). Пять секунд
	// стояли потому, что сверка была ЕДИНСТВЕННЫМ способом заметить потерю: кадр,
	// потерянный шиной, не замечал никто, и таймер отвечал за звонок.
	//
	// Теперь разрыв ловит клиент по номеру в своей полосе и зовёт `sync.pull` сам —
	// то есть замечает за один оборот сети, а не за пять секунд. Сверка осталась
	// страховкой на случай, когда потерялась сама подсказка, и на этой работе
	// тридцати секунд хватает с запасом.
	//
	// Заодно это снимает счёт, который иначе пришлось бы платить всегда: запрос на
	// соединение раз в пять секунд — это N/5 запросов в секунду при любом числе
	// живых устройств, даже когда им нечего доставлять.
	wsCatchupLimit = 100
)

// Переменные, а не константы: проверкам иначе пришлось бы ждать по полторы минуты на
// каждую, а протухший вызов не дождаться вовсе. Тот же приём, что у `deliveryNoticeAfter`
// и `ringRepokeAfter` в call_updates.go, и по той же причине — сроки здесь выбраны решением, а
// не измерены.
var (
	wsCatchupInterval = 30 * time.Second

	// Сколько живёт подсказка о звонке.
	//
	// Две минуты — предел, после которого разговор невозможен ни при каком раскладе:
	// вызов звонит сорок пять секунд, токен LiveKit живёт две минуты. Кадр старше
	// этого не отдаётся вовсе — но **отметка через него шагает**, иначе клиент
	// просит с того же места вечно, сервер вечно пропускает, и очередь встаёт на
	// пустом месте.
	wsCallFrameTTL = 2 * time.Minute
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

	// Первый кадр — auth {token, app?, stream?}
	authCtx, cancel := context.WithTimeout(r.Context(), wsAuthTimeout)
	var authFrame struct {
		Token string `json:"token"`
		// Своя версия и ряд сборок. Необязательны: сборка постарше их не шлёт, и
		// пускать её мы обязаны как прежде — API только расширяется.
		App    int    `json:"app"`
		Stream string `json:"stream"`
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

	// ── «ВАШЕ ПРИЛОЖЕНИЕ УСТАРЕЛО» ──────────────────────────────────────────
	//
	// Сказать это обязан канал, а не только ручка версии. Ручку спрашивают при запуске
	// и по кнопке, а соединение живёт сутками: сборка, переставшая понимать кадры,
	// сегодня просто перестаёт получать события — и отличить её от телефона в плохой
	// сети нечем. Ровно так realme двое суток был глух, и в журнале не было ни строки
	// о причине.
	//
	// **Три условия, и каждое обязательно.** Порог объявлен; ряд сборок наш; номер
	// ниже порога. Ряд здесь важен так же, как в предложении обновиться: у v1 сейчас
	// version_code 24, у v2 — 2, и порог чужого ряда выключил бы приложение по числу
	// из соседней вселенной.
	//
	// **Молчащая сборка проходит.** Та, что версию не назвала, старше этого правила, и
	// выключать её здесь значило бы менять решение о совместимости обрывом связи.
	if v := s.AppVer; v != nil && v.MinClient > 0 && authFrame.App > 0 &&
		authFrame.Stream != "" && authFrame.Stream == v.Stream && authFrame.App < v.MinClient {
		_ = writeJSON(r.Context(), conn, map[string]any{
			"event":        "app.outdated",
			"min_client":   v.MinClient,
			"version_name": v.VersionName,
		})
		conn.Close(websocket.StatusPolicyViolation, "сборка ниже порога совместимости")
		return
	}

	// Подписка ДО ok: после ok клиент вправе считать, что live-события не теряются
	sub, err := s.Events.Subscribe(r.Context(), deviceID)
	if err != nil {
		log.Printf("ws %s: subscribe: %v", deviceID, err)
		conn.Close(websocket.StatusInternalError, "шина событий недоступна")
		return
	}
	defer sub.Close()

	ctx := r.Context()
	// ── ВЕРШИНЫ ПОЛОС ЕДУТ В САМОМ `ok` ─────────────────────────────────────
	//
	// Клиент обязан узнать, где сейчас каждая полоса, **до** первой подсказки. Иначе
	// устройство, молчавшее неделю, сверяло бы свой давний номер с новым и объявляло
	// разрыв там, где его нет, — либо, наоборот, не замечало бы настоящего.
	//
	// Отдельным кадром это было бы вторым способом сказать то же самое; ошибка
	// хранилища здесь не повод рвать соединение — полосы тогда придут с первой
	// подсказкой, и клиент до тех пор просто не проверяет разрыв.
	hello := map[string]any{"event": "ok", "device_id": deviceID}
	if lanes, err := s.Store.DeviceLanes(ctx, deviceID); err == nil {
		hello["pts"], hello["qts"], hello["seq"] = lanes.Pts, lanes.Qts, lanes.Seq
	} else {
		log.Printf("ws %s: вершины полос: %v", deviceID, err)
	}
	// Вершина ленты звонков (ВЗ0а) — тем же приветствием и по той же причине: телефон,
	// вернувшийся в сеть, сверяет свой `cts` сразу и забирает пропущенное, не дожидаясь
	// подсказки, которой может и не быть.
	if cts, err := s.Store.CallTop(ctx, claims.Subject); err == nil {
		hello["cts"] = cts
	} else {
		log.Printf("ws %s: вершина ленты звонков: %v", deviceID, err)
	}
	if err := writeJSON(ctx, conn, hello); err != nil {
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
			if err := s.handleWSFrame(ctx, conn, deviceID, claims.Subject, f, &sent); err != nil {
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
			// ── ПОДСКАЗКА ПРОХОДИТ КАК ЕСТЬ, И `sent` НЕ ДВИГАЕТ ────────
			//
			// Раньше здесь ехало тело, и соединение отмечало его отданным. Теперь
			// едет «приходи и забери»: тела клиент не получил, и сдвинуть отметку
			// значило бы соврать самим себе — дожим перестал бы досылать ровно то,
			// ради чего заведён.
			//
			// Отметку двигает `sync.pull`, то есть настоящая отдача тел.
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
	Cts     int64  `json:"cts"`      // call.ack — лента звонков, ВЗ0а
}

// handleWSFrame — sync.pull и ack; typing/receipt/presence — следующие итерации.
// sent — докуда соединение отдало журнал; sync.pull его двигает.
func (s *Server) handleWSFrame(ctx context.Context, conn *websocket.Conn, deviceID, userID string, f wsClientFrame, sent *int64) error {
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
				// Полосы тоже обязаны сброситься: их номера остались от журнала,
				// которого больше нет. Не сбросишь — клиент навсегда считает, что у
				// него разрыв, и зовёт `sync.pull` на каждую подсказку.
				gap := map[string]any{"event": "sync.gap", "next_cursor": next}
				if lanes, err := s.Store.DeviceLanes(ctx, deviceID); err == nil {
					gap["pts"], gap["qts"], gap["seq"] = lanes.Pts, lanes.Qts, lanes.Seq
				}
				return writeJSON(ctx, conn, gap)
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
	case "call.ack":
		// Лента звонков — свой курсор, журнал сообщений не трогается (ВЗ0а).
		ackCalls(ctx, s.Store, s.notifier(), userID, deviceID, f.Cts)
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
		// ── КАДР ЗВОНКА: СРОК ДЛЯ ОБОИХ, ПОДСКАЗКА ТОЛЬКО ДЛЯ ВЫЗОВА ────────
		//
		// Тело двухдневной давности рассказало бы про звонок, которого давно нет:
		// телефон, пролежавший офлайн сутки, зазвонил бы по всем накопленным вызовам
		// разом — ровно это и было записано в клиенте как беда.
		//
		// **Отметка шагает через просроченное.** Не шагала бы — клиент просил с того
		// же места вечно, сервер вечно пропускал, и очередь встала бы на пустом
		// месте. Ту же ловушку уже ловили на испорченных событиях, ниже.
		//
		// Подсказкой становится только `call.incoming`. `call.state` адресный: одному
		// звонку он уходит с разными словами разным устройствам, и слова `taken` в
		// строке звонка нет — см. `pokeFor`.
		if e.EventType == "call.incoming" || e.EventType == "call.state" {
			*last = e.EventID
			if time.Since(e.CreatedAt) > wsCallFrameTTL {
				continue
			}
			if e.EventType == "call.incoming" {
				var body struct {
					CallID string `json:"call_id"`
				}
				if json.Unmarshal(e.Payload, &body) != nil || body.CallID == "" {
					continue
				}
				if err := writeJSON(ctx, conn, map[string]any{
					"event": "call.poke", "call_id": body.CallID,
				}); err != nil {
					return err
				}
				continue
			}
			frame := map[string]any{}
			if json.Unmarshal(e.Payload, &frame) != nil {
				continue
			}
			frame["event"] = e.EventType
			frame["event_id"] = e.EventID
			if err := writeJSON(ctx, conn, frame); err != nil {
				return err
			}
			continue
		}
		frame := map[string]any{}
		if err := json.Unmarshal(e.Payload, &frame); err != nil {
			// Отдать нечего, но отметку двигаем: иначе испорченное событие
			// перечитывается вечно.
			log.Printf("ws %s: событие %d повреждено: %v", deviceID, e.EventID, err)
			*last = e.EventID
			continue
		}
		frame["event"] = e.EventType
		frame["event_id"] = e.EventID
		// Полоса и номер в ней едут вместе с кадром: по ним клиент и ловит разрыв.
		// Ноль означает «номера нет» — кадр вне полос либо записанный до 0055, и
		// такой клиент просто применяет, не проверяя.
		if e.Lane != store.LaneNone {
			frame["lane"] = e.Lane
			frame["lane_seq"] = e.LaneSeq
		}
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
