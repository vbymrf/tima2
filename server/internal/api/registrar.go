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
	AppendDeviceEvent(ctx context.Context, deviceID, eventType string, payload []byte) (int64, error)
	// Возвращает store.Device: сужается НАБОР МЕТОДОВ, а не словарь. Заводить свой
	// тип устройства значило бы писать преобразование, которое однажды разойдётся
	// с оригиналом, — ровно то, от чего уходим.
	ListDevices(ctx context.Context, userID string) ([]store.Device, error)
}

// Publisher — живая шина. nil означает «шины нет»: событие уже записано, и
// устройство заберёт его следующим sync.pull.
type Publisher interface {
	Publish(ctx context.Context, deviceID, event string, eventID int64, payload map[string]any) error
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
	шина func() Publisher
}

// Device — событие одному устройству.
func (n *Notifier) Device(ctx context.Context, deviceID, event string, payload map[string]any) {
	raw, err := json.Marshal(payload)
	if err != nil {
		log.Printf("notify %s %s: marshal: %v", deviceID, event, err)
		return
	}
	eventID, err := n.store.AppendDeviceEvent(ctx, deviceID, event, raw)
	if err != nil {
		log.Printf("notify %s %s: append: %v", deviceID, event, err)
		return
	}
	шина := n.шина()
	if шина == nil {
		return
	}
	if err := шина.Publish(ctx, deviceID, event, eventID, payload); err != nil {
		// Живая доставка не фатальна: событие уже в логе.
		log.Printf("notify %s %s: publish: %v", deviceID, event, err)
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
