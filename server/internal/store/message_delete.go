// Удаление сообщения у всех (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §8): пометка, а не стирание —
// содержимое живёт до конца срока эпохи, как у всех сообщений; вернуть нельзя.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// ErrMessageGone — сообщения нет в этой переписке или оно уже удалено.
var ErrMessageGone = errors.New("сообщения нет или оно удалено")

// DeleteMessage ставит deleted, deleted_at, deleted_by у сообщения и у его правок (ADR-0045:
// правки скрываются вместе с исходным). group=false — личная переписка (container = chat_id),
// true — группа (container = group_id). allowForeign — можно ли чужое: в личной никогда, в
// группе — модератору и выше; решает вызывающий по роли.
//
// Возвращает устройства, на которые завёрнут ключ личного сообщения: им событие «удалено». У
// группы — nil, адресатов называет состав группы.
//
// Проверка и пометка — в одной транзакции под FOR UPDATE: правка, пришедшая в ту же минуту, не
// останется видимой у удалённого исходного.
func (s *Store) DeleteMessage(
	ctx context.Context, group bool, container string, messageID int64,
	requesterID string, allowForeign bool,
) ([]string, error) {
	table, key := "personal_messages", "chat_id"
	if group {
		table, key = "group_messages", "group_id"
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var sender string
	err = tx.QueryRow(ctx, `SELECT sender_id::text FROM `+table+`
		WHERE `+key+` = $1 AND message_id = $2 AND NOT deleted AND edit_of = 0 FOR UPDATE`,
		container, messageID).Scan(&sender)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return nil, ErrMessageGone
	} else if err != nil {
		return nil, err
	}
	if sender != requesterID && !allowForeign {
		return nil, ErrGroupMessageForeign
	}
	if _, err = tx.Exec(ctx, `UPDATE `+table+` SET deleted = TRUE, deleted_at = now(), deleted_by = $3
		WHERE `+key+` = $1 AND (message_id = $2 OR edit_of = $2) AND NOT deleted`,
		container, messageID, requesterID); err != nil {
		return nil, err
	}
	var devices []string
	if !group {
		rows, err := tx.Query(ctx, `SELECT DISTINCT recipient FROM personal_message_keys
			WHERE chat_id = $1 AND message_id = $2`, container, messageID)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var d string
			if err := rows.Scan(&d); err != nil {
				rows.Close()
				return nil, err
			}
			devices = append(devices, d)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	return devices, tx.Commit(ctx)
}
