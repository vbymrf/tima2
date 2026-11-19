// Закреплённые группой (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ12): одно сообщение или группа до 20 на переписку, группу,
// канал; у группы название. Отдельным типом: у `*Store` бюджет методов.
package store

import (
	"context"
	"errors"
	"time"
)

// PinsMax — сколько сообщений можно закрепить в одной переписке (ЗГ-1).
const PinsMax = 20

// ErrPinsFull — закреплено 20, больше нельзя.
var ErrPinsFull = errors.New("закреплено 20 — уберите лишние")

// PinBoard — закреплённые контейнера.
type PinBoard struct{ s *Store }

// PinBoardOf — закреплённые поверх хранилища.
func PinBoardOf(s *Store) PinBoard { return PinBoard{s: s} }

// PinItem — закреплённое сообщение.
type PinItem struct {
	MessageID int64
	By        string
	At        time.Time
}

// List — название (пусто — не задано) и закреплённые, новые первыми.
func (b PinBoard) List(ctx context.Context, kind, container string) (string, []PinItem, error) {
	var title string
	_ = b.s.pool.QueryRow(ctx, `SELECT title FROM pin_titles WHERE container_kind = $1 AND container_id = $2`, kind, container).Scan(&title)
	rows, err := b.s.pool.Query(ctx, `
		SELECT message_id, pinned_by::text, pinned_at FROM pin_items
		 WHERE container_kind = $1 AND container_id = $2 ORDER BY position DESC`, kind, container)
	if err != nil {
		return "", nil, err
	}
	defer rows.Close()
	var out []PinItem
	for rows.Next() {
		var it PinItem
		if err := rows.Scan(&it.MessageID, &it.By, &it.At); err != nil {
			return "", nil, err
		}
		out = append(out, it)
	}
	return title, out, rows.Err()
}

// Add — закрепить сообщения: [replace] — сначала снять всё. Уже закреплённое не добавляется — номера в ответе
// `already`. Чужое или неживое — ErrMessageGone (ничего не меняется); 21-е — ErrPinsFull.
func (b PinBoard) Add(ctx context.Context, kind, container string, ids []int64, replace bool, actorID string) (already []int64, err error) {
	target, ok := pinTarget[kind]
	if !ok {
		return nil, errors.New("неизвестный вид контейнера закрепления: " + kind)
	}
	tx, err := b.s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck
	if replace {
		if _, err := tx.Exec(ctx, `DELETE FROM pin_items WHERE container_kind = $1 AND container_id = $2`, kind, container); err != nil {
			return nil, err
		}
	}
	for _, id := range ids {
		var alive bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (`+target+`)`, container, id).Scan(&alive); err != nil {
			if isBadUUID(err) {
				return nil, ErrMessageGone
			}
			return nil, err
		}
		if !alive {
			return nil, ErrMessageGone
		}
		ct, err := tx.Exec(ctx, `
			INSERT INTO pin_items (container_kind, container_id, message_id, pinned_by) VALUES ($1, $2, $3, $4)
			ON CONFLICT DO NOTHING`, kind, container, id, actorID)
		if err != nil {
			return nil, err
		}
		if ct.RowsAffected() == 0 {
			already = append(already, id)
		}
	}
	var n int
	if err := tx.QueryRow(ctx, `SELECT count(*) FROM pin_items WHERE container_kind = $1 AND container_id = $2`, kind, container).Scan(&n); err != nil {
		return nil, err
	}
	if n > PinsMax {
		return nil, ErrPinsFull
	}
	return already, tx.Commit(ctx)
}

// Remove — снять выбранные; пустой список — все. Сколько снято.
func (b PinBoard) Remove(ctx context.Context, kind, container string, ids []int64) (int64, error) {
	if len(ids) == 0 {
		ct, err := b.s.pool.Exec(ctx, `DELETE FROM pin_items WHERE container_kind = $1 AND container_id = $2`, kind, container)
		if err != nil {
			return 0, err
		}
		return ct.RowsAffected(), nil
	}
	ct, err := b.s.pool.Exec(ctx, `
		DELETE FROM pin_items WHERE container_kind = $1 AND container_id = $2 AND message_id = ANY($3)`, kind, container, ids)
	if err != nil {
		return 0, err
	}
	return ct.RowsAffected(), nil
}

// Rename — название группы закреплённых; пусто — снять (на клиенте — «Группа сообщений»).
func (b PinBoard) Rename(ctx context.Context, kind, container, title, actorID string) error {
	if title == "" {
		_, err := b.s.pool.Exec(ctx, `DELETE FROM pin_titles WHERE container_kind = $1 AND container_id = $2`, kind, container)
		return err
	}
	_, err := b.s.pool.Exec(ctx, `
		INSERT INTO pin_titles (container_kind, container_id, title, set_by) VALUES ($1, $2, $3, $4)
		ON CONFLICT (container_kind, container_id) DO UPDATE SET title = EXCLUDED.title, set_by = EXCLUDED.set_by, set_at = now()`,
		kind, container, title, actorID)
	return err
}
