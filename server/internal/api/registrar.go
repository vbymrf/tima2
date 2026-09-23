package api

import (
	"context"
	"encoding/json"
	"log"
	"net/http"

	"tima/server/internal/store"
)

// Общая оснастка registrar-ов: middleware и уведомитель.
//
// ── ЗАЧЕМ REGISTRAR ─────────────────────────────────────────────────────────
//
// Handler, объявленный методом *Server, видит все 19 полей структуры. Отсюда
// растёт всё остальное: новый endpoint — строка в общем Register, метод на общем
// receiver, метод на общем Store. Два разработчика, делающие каналы и звонки, по
// смыслу не пересекаются, но обязательно пересекутся в этих трёх файлах: очередь
// на merge, конфликты и регрессии в коде, который ни один из них не открывал.
//
// Registrar разрывает это. Один bounded context — один файл: свободные функции
// вместо методов, узкий интерфейс вместо всего хранилища, свой Register вместо
// строки в общем. Новая функция становится файлом, а не правкой общего типа.

// Middleware — то, чем оборачивается handler перед регистрацией.
//
// Существующий requireActiveDevice приводится к этому типу и передаётся
// registrar-ам готовым: проверка устройства остаётся ровно в одном месте, и
// registrar не может её забыть — он получает уже обёрнутый вызов.
type Middleware func(http.HandlerFunc) http.HandlerFunc

// NotifyStore — то, что нужно уведомителю от хранилища, и ничего больше.
type NotifyStore interface {
	AppendDeviceEvent(ctx context.Context, deviceID, eventType string, payload []byte) (int64, store.Lanes, error)
	// Возвращает store.Device: сужается НАБОР МЕТОДОВ, а не словарь. Заводить свой
	// тип устройства значило бы писать преобразование, которое однажды разойдётся
	// с оригиналом, — ровно то, от чего уходим.
	ListDevices(ctx context.Context, userID string) ([]store.Device, error)
}

// Publisher — живая шина. nil означает «шины нет»: событие уже записано, и
// устройство заберёт его следующим sync.pull.
type Publisher interface {
	Publish(ctx context.Context, deviceID string, frame map[string]any) error
}

// Notifier — доставка события устройству: сначала персистентная запись, потом live.
//
// **Порядок нормативен.** device_events — источник догона sync.pull; опубликовать
// раньше, чем записал, значит допустить событие, которое видели онлайн-устройства
// и не увидит ни одно офлайновое. Handler-у этот порядок знать не нужно — и не
// нужно про него помнить: после выделения он не видит ни Events, ни очерёдности.
type Notifier struct {
	store NotifyStore
	// Шина берётся ФУНКЦИЕЙ и читается на каждое событие.
	//
	// Server.Events заполняется ПОСЛЕ Register — так делает и cmd/tima, и
	// setupWithEvents. Снимок при регистрации давал самую подлую поломку из
	// возможных: запись в device_events проходила, REST-проверки были зелёными,
	// а live-доставки не было вовсе — «сообщение отправлено, но не пришло».
	// Стоило это двух упавших WS-тестов, и хорошо, что они есть.
	bus func() Publisher
}

// Device — событие одному устройству.
//
// Возвращает номер события в журнале; 0 — записать не удалось. Номер нужен тому, кто
// потом спросит «забрало ли устройство этот кадр»: подтверждение приходит именно по
// нему (sync_cursors). Почти никто из вызывающих его не смотрит, и это правильно —
// знать про доставку поимённо нужно одному звонку.
func (n *Notifier) Device(ctx context.Context, deviceID, event string, payload map[string]any) int64 {
	raw, err := json.Marshal(payload)
	if err != nil {
		log.Printf("notify %s %s: marshal: %v", deviceID, event, err)
		return 0
	}
	eventID, lanes, err := n.store.AppendDeviceEvent(ctx, deviceID, event, raw)
	if err != nil {
		log.Printf("notify %s %s: append: %v", deviceID, event, err)
		return 0
	}
	bus := n.bus()
	if bus == nil {
		return eventID
	}
	if err := bus.Publish(ctx, deviceID, pokeFor(event, eventID, lanes, payload)); err != nil {
		// Живая доставка не фатальна: событие уже в логе.
		log.Printf("notify %s %s: publish: %v", deviceID, event, err)
	}
	return eventID
}

// pokeFor — какую подсказку слать про это событие.
//
// ── ТЕЛО ПО ШИНЕ БОЛЬШЕ НЕ ЕДЕТ ─────────────────────────────────────────────
//
// Едет «приходи и забери». Подсказка не несёт состояния — значит **протухнуть не
// может**: вызов недельной давности, доехавший до телефона, приводит не к звонку, а к
// запросу, который честно отвечает «кончился». Целый класс бед исчезает не починкой, а
// устройством.
//
// ── ДВЕ ПОДСКАЗКИ, И ГРАНИЦА МЕЖДУ НИМИ — НЕ ПРЕФИКС ────────────────────────
//
// `call.poke` уходит только про **состояние звонка**, потому что у состояния есть своя
// ручка (`GET /calls/{id}`), которая отвечает правду на момент вопроса. `call.unreachable`
// состоянием звонка не является — это наблюдение соединения, и забирается оно обычным
// путём, вместе со всем прочим.
//
// Всё остальное — `sync.poke`. Он несёт два разных сведения, и оба нужны:
//
//   - `event_id` — **спусковой крючок**: больше моего курсора, значит есть что забрать.
//     Он один обеспечивает правильность: потеряйся подсказка, следующая всё равно будет
//     с бóльшим номером.
//   - вершины полос — **диагноз**: по ним видно, что именно потерялось и где. «В `qts`
//     пропущено одно» — это сразу «жди нечитаемых сообщений», а не «что-то потерялось».
func pokeFor(event string, eventID int64, lanes store.Lanes, payload map[string]any) map[string]any {
	if event == "call.incoming" || event == "call.state" {
		if callID, ok := payload["call_id"].(string); ok && callID != "" {
			return map[string]any{"event": "call.poke", "call_id": callID}
		}
	}
	return map[string]any{
		"event":    "sync.poke",
		"event_id": eventID,
		"pts":      lanes.Pts,
		"qts":      lanes.Qts,
		"seq":      lanes.Seq,
	}
}

// Users — то же событие всем устройствам перечисленных людей.
//
// Отказ по одному человеку не останавливает рассылку: у остальных событие уже
// записано, и терять его из-за чужой ошибки нельзя.
func (n *Notifier) Users(ctx context.Context, userIDs []string, event string, payload map[string]any) {
	for _, uid := range userIDs {
		devices, err := n.store.ListDevices(ctx, uid)
		if err != nil {
			log.Printf("notify users %s: devices of %s: %v", event, uid, err)
			continue
		}
		for _, d := range devices {
			n.Device(ctx, d.DeviceID, event, payload)
		}
	}
}
