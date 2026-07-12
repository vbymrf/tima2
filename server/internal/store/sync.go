// Event log устройств и sync-cursor (sync-offline.md §2). События идемпотентны
// (доменные id внутри payload), повторная выдача после обрыва безопасна.
package store

import (
	"context"
)

type DeviceEvent struct {
	EventID   int64
	EventType string
	Payload   []byte // JSON: поля кадра без event/event_id
}

// AppendDeviceEvent кладёт событие в лог устройства, event_id назначает база.
func (s *Store) AppendDeviceEvent(ctx context.Context, deviceID, eventType string, payload []byte) (int64, error) {
	var id int64
	err := s.pool.QueryRow(ctx, `
		INSERT INTO device_events (device_id, event_type, payload)
		VALUES ($1, $2, $3) RETURNING event_id`,
		deviceID, eventType, payload).Scan(&id)
	return id, err
}

// ListDeviceEvents — события устройства с event_id > after (sync.pull).
func (s *Store) ListDeviceEvents(ctx context.Context, deviceID string, after int64, limit int) ([]DeviceEvent, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.pool.Query(ctx, `
		SELECT event_id, event_type, payload FROM device_events
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
		if err := rows.Scan(&e.EventID, &e.EventType, &e.Payload); err != nil {
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
