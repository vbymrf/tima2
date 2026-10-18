// Правка сообщения (ADR-0045): что знать об исходном, чтобы принять или отвергнуть правку.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// ErrEditTargetMissing — исходного нет в этой переписке или оно удалено.
var ErrEditTargetMissing = errors.New("исправляемого сообщения нет")

// EditTarget — исходное сообщение правки.
type EditTarget struct {
	SenderID string
	// EditOf — у самого исходного; не 0 — это правка, а правку не правят.
	EditOf int64
	// Edits — сколько правок у исходного уже принято (Решения §8: не больше трёх).
	Edits int
	// SameMonth — исходное принято в нынешнем месяце UTC, то есть в той же эпохе ключа личной
	// переписки (ADR-0036). Время сервера, а не время из конверта: его пишет клиент.
	SameMonth bool
	// GKVersion — версия ключа группы исходного; у личной переписки и открытого — 0.
	GKVersion int32
	// Level — уровень исходного в группе (ADR-0019): правка получает его же, иначе её текст
	// увидел бы тот, кому исходное не показывают.
	Level int16
}

// EditTarget — исходное для правки. group=false — личная переписка (container = chat_id),
// true — группа (container = group_id).
//
// Один метод на оба вида, а не два: архитектурный тест держит число методов хранилища, а
// вопрос у обоих видов один и тот же. Проверка и запись идут разными запросами: две правки
// одного сообщения в одну и ту же миллисекунду могут обе пройти счёт «меньше трёх» — четвёртая
// правка в таком случае ничего не ломает, она лишь лишняя.
func (s *Store) EditTarget(ctx context.Context, group bool, container string, messageID int64) (EditTarget, error) {
	var t EditTarget
	var err error
	if group {
		err = s.pool.QueryRow(ctx, `
			SELECT m.sender_id::text, m.edit_of, COALESCE(m.gk_version, 0), m.level,
			       (SELECT count(*) FROM group_messages e
			         WHERE e.group_id = m.group_id AND e.edit_of = m.message_id AND NOT e.deleted)
			  FROM group_messages m
			 WHERE m.group_id = $1 AND m.message_id = $2 AND NOT m.deleted`,
			container, messageID).Scan(&t.SenderID, &t.EditOf, &t.GKVersion, &t.Level, &t.Edits)
	} else {
		err = s.pool.QueryRow(ctx, `
			SELECT m.sender_id::text, m.edit_of,
			       date_trunc('month', m.received_at AT TIME ZONE 'UTC') = date_trunc('month', now() AT TIME ZONE 'UTC'),
			       (SELECT count(*) FROM personal_messages e
			         WHERE e.chat_id = m.chat_id AND e.edit_of = m.message_id AND NOT e.deleted)
			  FROM personal_messages m
			 WHERE m.chat_id = $1 AND m.message_id = $2 AND NOT m.deleted`,
			container, messageID).Scan(&t.SenderID, &t.EditOf, &t.SameMonth, &t.Edits)
	}
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return EditTarget{}, ErrEditTargetMissing
	}
	return t, err
}
