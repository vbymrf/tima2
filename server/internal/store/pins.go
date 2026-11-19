// Закреплённое сообщение (doc_mig/ПЛАН-(ЗО)-ЗАКРЕПЛЕНИЯ-И-ОПИСАНИЯ.md, ЗО1): одно на личную
// переписку, группу, канал. Сервер знает номер закреплённого открыто — «иначе никто не узнает;
// метаданные у нас открытые» (заказчик 2026-10-18). Права решает вызывающий: хранилище проверяет
// только, что номер — живое сообщение этого же контейнера.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// Виды контейнера закрепления — значения колонки pins.container_kind.
const (
	PinPersonal = "personal"
	PinGroup    = "group"
	PinChannel  = "channel"
)

// Pin — закреплённое: номер, кто и когда. MessageID 0 — ничего не закреплено.
type Pin struct {
	MessageID int64
	By        string
	At        time.Time
}

// pinTarget — живое сообщение контейнера, которое можно закрепить: не удалено, не правка, у канала
// — запись, а не комментарий.
var pinTarget = map[string]string{
	PinPersonal: `SELECT 1 FROM personal_messages WHERE chat_id = $1::text::uuid AND message_id = $2 AND NOT deleted AND edit_of = 0`,
	PinGroup:    `SELECT 1 FROM group_messages WHERE group_id = $1::text::uuid AND message_id = $2 AND NOT deleted AND edit_of = 0`,
	PinChannel: `SELECT 1 FROM channel_posts WHERE channel_id = $1::text::uuid AND post_id = $2 AND NOT deleted
		AND parent_post_id IS NULL`,
}

// SetPin закрепляет сообщение — с 2026-11-19 добавляет его к закреплённым (ПЛАН-(СЦ) СЦ12, ЗГ-1: «закрепили
// второе — одно становится группой»); уже закреплено — ничего. Нет такого живого сообщения в контейнере —
// ErrMessageGone; закреплено 20 — ErrPinsFull.
func (s *Store) SetPin(ctx context.Context, kind, container string, messageID int64, actorID string) error {
	_, err := PinBoardOf(s).Add(ctx, kind, container, []int64{messageID}, false, actorID)
	return err
}

// ClearPin снимает закрепление. Было ли что снимать — второе значение: событие «откреплено» без
// закреплённого слать незачем.
//
// С 2026-11-19 (СЦ12) снимает всё закреплённое списком: прежний клиент знает одно и снимает «его».
func (s *Store) ClearPin(ctx context.Context, kind, container string) (bool, error) {
	ct, err := s.pool.Exec(ctx, `DELETE FROM pin_items WHERE container_kind = $1 AND container_id = $2`, kind, container)
	if err != nil {
		return false, err
	}
	return ct.RowsAffected() > 0, nil
}

// PinOf — что закреплено в контейнере; пустой Pin — ничего. Из списка (СЦ12) — последнее закреплённое.
func (s *Store) PinOf(ctx context.Context, kind, container string) (Pin, error) {
	var p Pin
	err := s.pool.QueryRow(ctx, `SELECT message_id, pinned_by::text, pinned_at FROM pin_items
		WHERE container_kind = $1 AND container_id = $2 ORDER BY position DESC LIMIT 1`, kind, container).Scan(&p.MessageID, &p.By, &p.At)
	if errors.Is(err, pgx.ErrNoRows) {
		return Pin{}, nil
	}
	return p, err
}

// ChatUsers — участники личной переписки: отправители и владельцы устройств-адресатов. Таблицы
// переписок нет, номер выводится (ADR-0010), поэтому состав читается по сообщениям.
func (s *Store) ChatUsers(ctx context.Context, chatID string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT sender_id::text FROM personal_messages WHERE chat_id = $1
		UNION
		SELECT d.user_id::text FROM personal_message_keys k JOIN devices d ON d.device_id = k.recipient
		 WHERE k.chat_id = $1`, chatID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// unpinDeletedSQL — удалённое закреплённое открепляется само (решение заказчика 2026-10-18).
// Выполняется удалениями тем же исполнителем, что и пометка: $1 вид, $2 контейнер, $3 номер.
const unpinDeletedSQL = `DELETE FROM pin_items WHERE container_kind = $1 AND container_id = $2 AND message_id = $3`
