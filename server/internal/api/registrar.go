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
	// Лента звонков (0056): изменение звонка — строка с номером `cts` в ленте человека.
	AppendCallUpdate(ctx context.Context, userID, callID, change, deviceID string) (int64, error)
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

// CallChange — изменение звонка в ленту человека и подсказка всем его устройствам
// (ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md, ВЗ0а).
//
// ── ЖУРНАЛ УСТРОЙСТВА НЕ ТРОГАЕТСЯ ──────────────────────────────────────────
//
// Звонок живёт своей лентой: номер `cts` на человека, подсказка `call.poke {cts}`,
// подтверждение `call.ack {cts}` двигает только курсор звонков. Раньше вызов лежал в
// журнале рядом с сообщениями, и подтвердить его подсказку было нечем, не перескочив
// через незабранные сообщения, — отсюда было ложное «не в сети».
//
// Возвращает номер изменения; 0 — записать не удалось, и подсказки не будет: подсказка
// без строки звала бы за тем, чего нет.
func (n *Notifier) CallChange(ctx context.Context, userID, callID, change, deviceID string) int64 {
	cts, err := n.store.AppendCallUpdate(ctx, userID, callID, change, deviceID)
	if err != nil {
		log.Printf("call change %s %s %s: %v", userID, callID, change, err)
		return 0
	}
	n.CallPoke(ctx, userID, cts)
	return cts
}

// CallPoke — подсказка «в ленте звонков есть до №cts» всем устройствам человека.
//
// Отдельно от CallChange, потому что повтор вызова (`repokeRinging`) шлёт подсказку ещё
// раз, не заводя новой строки: изменение одно, а подсказка по шине — «не более одного
// раза».
func (n *Notifier) CallPoke(ctx context.Context, userID string, cts int64) {
	bus := n.bus()
	if bus == nil || cts <= 0 {
		return
	}
	devices, err := n.store.ListDevices(ctx, userID)
	if err != nil {
		log.Printf("call poke %s: devices: %v", userID, err)
		return
	}
	for _, d := range devices {
		if err := bus.Publish(ctx, d.DeviceID, map[string]any{"event": "call.poke", "cts": cts}); err != nil {
			log.Printf("call poke %s: publish: %v", d.DeviceID, err)
		}
	}
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
// ── ПОДСКАЗКОЙ СТАНОВИТСЯ ТОЛЬКО ВЫЗОВ, И ЭТО ОТСТУПЛЕНИЕ ОТ ПЛАНА ──────────
//
// План говорил «кадры звонка становятся подсказкой» — все. При разборе выяснилось, что
// `call.state` так нельзя, и причина не в осторожности.
//
// `call.state` **адресный**: одному и тому же звонку он уходит с разными словами разным
// устройствам. Ответившему человеку на остальные его телефоны идёт `taken` — «закрой у
// себя окно, разговор идёт на другом», — а звонящему `answered`. Строка звонка в базе
// одна, и слова `taken` в ней нет: сервер знает, КТО ответил, а «это не ты» — знание
// соединения, а не звонка. Сделай `call.state` подсказкой, и телефон, не бравший трубку,
// прочитал бы в ручке `answered` и решил, что разговор у него.
//
// `call.incoming` адресности лишён: он говорит «тебе звонят», и это ровно то, что ручка
// подтвердит или опровергнет. Беда, ради которой план и писался, — телефон, пролежавший
// офлайн сутки, звонит по всем накопленным вызовам — вся целиком здесь.
//
// `call.unreachable` состоянием звонка не является вовсе: это наблюдение соединения.
// Забирается обычным путём, вместе со всем прочим.
//
// Протухание `call.state` при этом закрыто тем же сроком в две минуты (`ws.go`).
//
// Всё остальное — `sync.poke`. Он несёт два разных сведения, и оба нужны:
//
//   - `event_id` — **спусковой крючок**: больше моего курсора, значит есть что забрать.
//     Он один обеспечивает правильность: потеряйся подсказка, следующая всё равно будет
//     с бóльшим номером.
//   - вершины полос — **диагноз**: по ним видно, что именно потерялось и где. «В `qts`
//     пропущено одно» — это сразу «жди нечитаемых сообщений», а не «что-то потерялось».
func pokeFor(event string, eventID int64, lanes store.Lanes, payload map[string]any) map[string]any {
	if event == "call.incoming" {
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
