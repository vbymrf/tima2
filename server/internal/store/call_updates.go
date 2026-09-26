// Лента звонков — своя переменная `cts` (ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md, ВЗ0а; миграция 0056).
package store

import (
	"context"
	"time"
)

// CallUpdate — одно изменение звонка в ленте человека.
//
// Вместе со строкой едет снимок звонка на момент ЧТЕНИЯ, а не записи: телефон, забравший
// ленту через минуту, должен узнать, что звонок уже кончился, а не звонить по тому, что
// было правдой тогда.
type CallUpdate struct {
	Cts      int64
	CallID   string
	Change   string
	DeviceID string // у `answered` — ответившее устройство; пусто — ни к какому
	At       time.Time
	Call     CallRow
}

// AppendCallUpdate — записать изменение в ленту человека и вернуть его номер.
//
// Номер выдаётся тем же запросом, что пишется строка: вершина растёт в `call_tops`
// атомарно, и два изменения одного человека не получат один номер.
func (s *Store) AppendCallUpdate(ctx context.Context, userID, callID, change, deviceID string) (int64, error) {
	var dev any
	if deviceID != "" {
		dev = deviceID
	}
	var cts int64
	err := s.pool.QueryRow(ctx, `
		WITH top AS (
			INSERT INTO call_tops (user_id, cts) VALUES ($1, 1)
			ON CONFLICT (user_id) DO UPDATE SET cts = call_tops.cts + 1
			RETURNING cts
		)
		INSERT INTO call_updates (user_id, cts, call_id, change, device_id)
		SELECT $1, cts, $2, $3, $4::uuid FROM top
		RETURNING cts`, userID, callID, change, dev).Scan(&cts)
	return cts, err
}

// CallTop — вершина ленты человека; 0 — изменений не было ни разу.
func (s *Store) CallTop(ctx context.Context, userID string) (int64, error) {
	var cts int64
	err := s.pool.QueryRow(ctx,
		`SELECT COALESCE((SELECT cts FROM call_tops WHERE user_id = $1), 0)`, userID).Scan(&cts)
	if isBadUUID(err) {
		return 0, nil
	}
	return cts, err
}

// ListCallUpdates — изменения после `after`, по возрастанию номера, со снимком звонка.
//
// «Разрыв» здесь не решается: его видит вызывающий по первому номеру (не `after+1` —
// значит начало ленты уже вычищено).
func (s *Store) ListCallUpdates(ctx context.Context, userID string, after int64, limit int) ([]CallUpdate, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := s.pool.Query(ctx, `
		SELECT u.cts, u.call_id::text, u.change, COALESCE(u.device_id::text, ''), u.created_at,
		       c.kind, c.state, c.initiator_id::text, COALESCE(c.peer_id::text, ''),
		       COALESCE(c.ended_by::text, ''), c.created_at, c.answered_at, c.ended_at
		  FROM call_updates u
		  JOIN calls c ON c.call_id = u.call_id
		 WHERE u.user_id = $1 AND u.cts > $2
		 ORDER BY u.cts
		 LIMIT $3`, userID, after, limit)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []CallUpdate
	for rows.Next() {
		var u CallUpdate
		var answered, ended *time.Time
		if err := rows.Scan(&u.Cts, &u.CallID, &u.Change, &u.DeviceID, &u.At,
			&u.Call.Kind, &u.Call.State, &u.Call.InitiatorID, &u.Call.PeerID,
			&u.Call.EndedBy, &u.Call.CreatedAt, &answered, &ended); err != nil {
			return nil, err
		}
		u.Call.CallID = u.CallID
		if answered != nil {
			u.Call.AnsweredAt = *answered
		}
		if ended != nil {
			u.Call.EndedAt = *ended
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// SetCallCursor — докуда устройство подтвердило ленту. Назад не двигается: подтверждение
// с отставшего соединения не должно откатывать более свежее.
func (s *Store) SetCallCursor(ctx context.Context, deviceID string, cts int64) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO call_cursors (device_id, cts) VALUES ($1, $2)
		ON CONFLICT (device_id)
		DO UPDATE SET cts = GREATEST(call_cursors.cts, EXCLUDED.cts), updated_at = now()`,
		deviceID, cts)
	return err
}

// DeliveredCall — вызов, который подтверждение сделало доставленным: кому сказать.
type DeliveredCall struct {
	CallID      string
	InitiatorID string
}

// DeliverCalls — отметить доставленными звонки, чей вызов покрыло подтверждение `upTo`.
//
// Одним запросом и только впервые (`delivered_at IS NULL`): второе подтверждение того же
// вызова — с соседнего устройства человека или повтором — звонящему ничего нового не
// говорит, и строки «доставлен» дважды быть не должно.
func (s *Store) DeliverCalls(ctx context.Context, userID string, upTo int64) ([]DeliveredCall, error) {
	rows, err := s.pool.Query(ctx, `
		UPDATE calls c SET delivered_at = now()
		  FROM call_updates u
		 WHERE u.user_id = $1 AND u.cts <= $2 AND u.change = 'ringing'
		   AND u.call_id = c.call_id AND c.state = 'ringing' AND c.delivered_at IS NULL
		RETURNING c.call_id::text, c.initiator_id::text`, userID, upTo)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []DeliveredCall
	for rows.Next() {
		var d DeliveredCall
		if err := rows.Scan(&d.CallID, &d.InitiatorID); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// GCCallUpdates — вычистить ленту старше срока (сутки — решение заказчика 2026-09-26).
//
// Вершины (`call_tops`) не трогаются: номер обязан только расти, иначе телефон, чей
// курсор выше новой вершины, никогда не увидел бы новых изменений.
func (s *Store) GCCallUpdates(ctx context.Context, olderThan time.Duration) (int64, error) {
	tag, err := s.pool.Exec(ctx,
		`DELETE FROM call_updates WHERE created_at < now() - make_interval(secs => $1)`,
		olderThan.Seconds())
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}
