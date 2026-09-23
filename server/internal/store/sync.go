// Event log устройств и sync-cursor (sync-offline.md §2). События идемпотентны
// (доменные id внутри payload), повторная выдача после обрыва безопасна.
package store

import (
	"context"
	"time"
)

type DeviceEvent struct {
	EventID   int64
	EventType string
	Payload   []byte // JSON: поля кадра без event/event_id

	// Когда событие записано. Нужно сроку кадра звонка: подсказка старше двух минут
	// не отдаётся вовсе, потому что за это время звонок успел кончиться.
	CreatedAt time.Time

	// Полоса и номер в ней — П3, миграция 0055.
	//
	// `Lane == LaneNone` означает «номера нет»: кадр звонка либо запись, сделанная до
	// перехода. Клиент такой кадр просто применяет, не проверяя на разрыв.
	Lane    int16
	LaneSeq int64
}

// Полосы доставки. Критерий разделения — **опасность разрыва**, не объём.
const (
	// LaneNone — вне полос: кадры звонка и всё, записанное до 0055.
	//
	// У звонка номера нет намеренно. Состояние берётся ручкой `GET /calls/{id}`, а
	// протухший кадр создавал бы дыру в полосе, которую пришлось бы объяснять клиенту.
	LaneNone int16 = 0

	// LanePts — переписка. Потерянный кадр = потерянное сообщение.
	LanePts int16 = 1

	// LaneQts — ключи. Пропущенная ротация означает «сообщение не откроется никогда».
	LaneQts int16 = 2

	// LaneSeq — фон: комментарии, дальше присутствие и счётчики. Важен факт, не порядок.
	LaneSeq int16 = 3
)

// LaneOf — в какой полосе едет событие такого вида.
//
// Раскладка живёт здесь, рядом с записью, а не у каждого отправителя: полоса — свойство
// события, а не того, кто его послал. Разойдись эти решения — и одно и то же событие
// поехало бы в разных полосах у разных ручек, то есть получило бы два номера.
//
// **Неизвестный вид уходит вне полос**, а не в `seq`. Событие без номера клиент
// применяет и на разрыв не проверяет — это честное «мы про него ничего не обещаем».
// Поставь его в полосу по умолчанию, и забытая раскладка означала бы ложный разрыв у
// всех, кто это событие не получил.
func LaneOf(eventType string) int16 {
	switch eventType {
	case "message.new", "message.group", "message.level_narrowed":
		return LanePts
	case "key.rotated", "recovery.gk_request", "recovery.gk_ready", "group.rotation_needed":
		return LaneQts
	case "channel.comment":
		return LaneSeq
	default:
		return LaneNone
	}
}

// AppendDeviceEvent кладёт событие в лог устройства, event_id назначает база.
//
// Возвращает номер события в журнале и **вершины трёх полос устройства** после записи:
// их шина кладёт в подсказку `sync.poke`, а клиент по ним ловит разрыв (П3, П4).
//
// ── ПОЧЕМУ ОДНИМ ЗАПРОСОМ, А НЕ ТРЕМЯ ───────────────────────────────────────
//
// Номер плотный внутри полосы и внутри устройства, значит его надо прочитать,
// прибавить и записать — и всё это так, чтобы между шагами не влезла вторая запись
// тому же устройству. `INSERT … RETURNING` в CTE делает это одной командой, то есть
// одной транзакцией по построению: отдельные `SELECT` и `UPDATE` открыли бы окно, в
// которое два события получили бы один номер.
//
// Строка `sync_cursors` заводится здесь же, если её ещё нет: до первого `ack` её не
// существует, а номер полосы нужен с первого события.
func (s *Store) AppendDeviceEvent(ctx context.Context, deviceID, eventType string, payload []byte) (int64, Lanes, error) {
	lane := LaneOf(eventType)
	var id int64
	var tops Lanes
	// Вне полос номер не назначается вовсе: строка sync_cursors при этом не трогается,
	// а вершины читаются как есть — подсказка про такой кадр ничего не обещает.
	err := s.pool.QueryRow(ctx, `
		WITH bumped AS (
			INSERT INTO sync_cursors (device_id, pts, qts, seq)
			VALUES ($1, CASE WHEN $4 = 1 THEN 1 ELSE 0 END,
			            CASE WHEN $4 = 2 THEN 1 ELSE 0 END,
			            CASE WHEN $4 = 3 THEN 1 ELSE 0 END)
			ON CONFLICT (device_id) DO UPDATE SET
				pts = sync_cursors.pts + CASE WHEN $4 = 1 THEN 1 ELSE 0 END,
				qts = sync_cursors.qts + CASE WHEN $4 = 2 THEN 1 ELSE 0 END,
				seq = sync_cursors.seq + CASE WHEN $4 = 3 THEN 1 ELSE 0 END
			RETURNING pts, qts, seq
		)
		INSERT INTO device_events (device_id, event_type, payload, lane, lane_seq)
		SELECT $1, $2, $3, $4,
		       CASE $4 WHEN 1 THEN b.pts WHEN 2 THEN b.qts WHEN 3 THEN b.seq ELSE 0 END
		FROM bumped b
		RETURNING event_id,
		          (SELECT pts FROM bumped), (SELECT qts FROM bumped), (SELECT seq FROM bumped)`,
		deviceID, eventType, payload, lane).Scan(&id, &tops.Pts, &tops.Qts, &tops.Seq)
	return id, tops, err
}

// Lanes — вершины трёх полос устройства: докуда доехал каждый счётчик.
//
// Едут в подсказке `sync.poke` и ни в чём больше. Тела события в ней нет — подсказка
// поэтому **протухнуть не может**, и это главное, что она даёт сверх экономии.
type Lanes struct {
	Pts int64 `json:"pts"`
	Qts int64 `json:"qts"`
	Seq int64 `json:"seq"`
}

// DeviceLanes — вершины полос устройства без записи события.
//
// Нужны соединению при подключении: клиент должен узнать, где сейчас каждая полоса, не
// дожидаясь следующего события. Иначе первый же разрыв после долгого молчания был бы
// замечен только со следующим кадром — то есть тогда же, когда и без полос.
func (s *Store) DeviceLanes(ctx context.Context, deviceID string) (Lanes, error) {
	var l Lanes
	err := s.pool.QueryRow(ctx, `
		SELECT COALESCE(pts, 0), COALESCE(qts, 0), COALESCE(seq, 0)
		FROM sync_cursors WHERE device_id = $1`, deviceID).Scan(&l.Pts, &l.Qts, &l.Seq)
	if err != nil && err.Error() == "no rows in result set" {
		// Устройству ещё ничего не слали — все полосы в нуле, и это не ошибка.
		return Lanes{}, nil
	}
	return l, err
}

// ListDeviceEvents — события устройства с event_id > after (sync.pull).
func (s *Store) ListDeviceEvents(ctx context.Context, deviceID string, after int64, limit int) ([]DeviceEvent, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.pool.Query(ctx, `
		SELECT event_id, event_type, payload, created_at, lane, lane_seq FROM device_events
		WHERE device_id = $1 AND event_id > $2
		ORDER BY event_id
		LIMIT $3`, deviceID, after, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceEvent
	for rows.Next() {
		var e DeviceEvent
		if err := rows.Scan(&e.EventID, &e.EventType, &e.Payload, &e.CreatedAt, &e.Lane, &e.LaneSeq); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// SetSyncCursor сдвигает cursor устройства вперёд (назад не двигается: ack
// с отставшего соединения не должен откатывать более свежий).
func (s *Store) SetSyncCursor(ctx context.Context, deviceID string, cursor int64) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO sync_cursors (device_id, cursor) VALUES ($1, $2)
		ON CONFLICT (device_id)
		DO UPDATE SET cursor = GREATEST(sync_cursors.cursor, EXCLUDED.cursor), updated_at = now()`,
		deviceID, cursor)
	return err
}

// SyncCursor — серверная копия cursor (0, если устройство ещё не ack-ало).
func (s *Store) SyncCursor(ctx context.Context, deviceID string) (int64, error) {
	var cursor int64
	err := s.pool.QueryRow(ctx,
		`SELECT COALESCE((SELECT cursor FROM sync_cursors WHERE device_id = $1), 0)`,
		deviceID).Scan(&cursor)
	return cursor, err
}
