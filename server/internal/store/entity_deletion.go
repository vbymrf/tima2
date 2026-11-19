// Удаление сущности с месяцем на отмену (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ14). Отдельным типом: у `*Store` бюджет
// методов (architecture_test).
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ErrNotScheduled — удаление не назначено или срок уже прошёл: отменять нечего.
var ErrNotScheduled = errors.New("удаление не назначено")

// Deletions — сроки удаления групп, каналов, сообществ.
type Deletions struct{ s *Store }

// DeletionsOf — сроки удаления поверх хранилища.
func DeletionsOf(s *Store) Deletions { return Deletions{s: s} }

func entityTable(kind string) (table, key string) {
	switch kind {
	case "channel":
		return "channels", "channel_id"
	case "community":
		return "communities", "community_id"
	default:
		return "groups", "group_id"
	}
}

// DeleteAt — срок удаления сущности; nil — не назначено.
func (d Deletions) DeleteAt(ctx context.Context, kind, id string) (*time.Time, error) {
	table, key := entityTable(kind)
	var at *time.Time
	err := d.s.pool.QueryRow(ctx, `SELECT delete_at FROM `+table+` WHERE `+key+` = $1 AND deleted_at IS NULL`, id).Scan(&at)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return nil, nil
	}
	return at, err
}

// Schedule — назначить удаление через [after]. Уже назначено — срок не сдвигается: повтор «удалить» не продлевает.
func (d Deletions) Schedule(ctx context.Context, kind, id string, after time.Duration) (time.Time, error) {
	table, key := entityTable(kind)
	var at time.Time
	err := d.s.pool.QueryRow(ctx, `
		UPDATE `+table+` SET delete_at = COALESCE(delete_at, now() + make_interval(secs => $2))
		 WHERE `+key+` = $1 AND deleted_at IS NULL RETURNING delete_at`, id, after.Seconds()).Scan(&at)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return at, ErrGroupNotFound
	}
	return at, err
}

// Cancel — отменить, пока срок не прошёл.
func (d Deletions) Cancel(ctx context.Context, kind, id string) error {
	table, key := entityTable(kind)
	ct, err := d.s.pool.Exec(ctx, `
		UPDATE `+table+` SET delete_at = NULL
		 WHERE `+key+` = $1 AND deleted_at IS NULL AND delete_at IS NOT NULL AND delete_at > now()`, id)
	if isBadUUID(err) || (err == nil && ct.RowsAffected() == 0) {
		return ErrNotScheduled
	}
	return err
}

// Due — удалить то, чей срок прошёл: то же, что прежнее мгновенное удаление (`deleted_at`). Число удалённых.
func (d Deletions) Due(ctx context.Context) (int64, error) {
	var total int64
	for _, kind := range []string{"group", "channel", "community"} {
		table, _ := entityTable(kind)
		ct, err := d.s.pool.Exec(ctx, `
			UPDATE `+table+` SET deleted_at = now()
			 WHERE deleted_at IS NULL AND delete_at IS NOT NULL AND delete_at <= now()`)
		if err != nil {
			return total, err
		}
		total += ct.RowsAffected()
	}
	return total, nil
}
