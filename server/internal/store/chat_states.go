package store

// Архив чата и срок жизни аккаунта без номера (миграция 0024).

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// SetChatArchived убирает чат в архив или возвращает обратно — для ОДНОГО человека.
//
// Архивирование личное: «убрать с глаз». Один участник не должен своей уборкой
// назначать удаление переписки другому.
func (s *Store) SetChatArchived(ctx context.Context, chatID, userID string, archived bool) error {
	// Время берём у БАЗЫ, а не у процесса. Отметка потом сравнивается с now() базы,
	// и если писать её часами приложения, расхождение часов (а бэкенд и база — разные
	// машины) сдвигает отметку в будущее, и чат никогда не становится готовым к
	// удалению. Одна величина — один источник времени.
	_, err := s.pool.Exec(ctx, `
		INSERT INTO chat_states (chat_id, user_id, archived_at)
		VALUES ($1, $2, CASE WHEN $3 THEN now() ELSE NULL END)
		ON CONFLICT (chat_id, user_id)
		DO UPDATE SET archived_at = CASE WHEN $3 THEN now() ELSE NULL END, updated_at = now()`,
		chatID, userID, archived)
	return err
}

// IsChatArchivedFor — убран ли чат в архив у конкретного человека.
func (s *Store) IsChatArchivedFor(ctx context.Context, chatID, userID string) (bool, error) {
	var at *time.Time
	err := s.pool.QueryRow(ctx,
		`SELECT archived_at FROM chat_states WHERE chat_id = $1 AND user_id = $2`, chatID, userID).Scan(&at)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return at != nil, nil
}

// ArchivedChatsFor — что человек убрал в архив (для отдельной вкладки в списке).
func (s *Store) ArchivedChatsFor(ctx context.Context, userID string) ([]string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT chat_id FROM chat_states WHERE user_id = $1 AND archived_at IS NOT NULL`, userID)
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

// ChatsArchivedByEveryone — чаты, которые убрали в архив ВСЕ их участники и дольше
// чем на срок. Только такие подлежат пометке на удаление.
//
// Участники личного чата определяются по переписке: кто в нём писал или кому в нём
// адресованы обёртки. Отдельного списка участников у личного чата нет — chat_id
// вычисляется из пары собеседников.
func (s *Store) ChatsArchivedByEveryone(ctx context.Context, days, limit int) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		WITH participants AS (
		    -- Отправители
		    SELECT DISTINCT m.chat_id, m.sender_id AS user_id FROM personal_messages m
		    UNION
		    -- Получатели: обёртка адресована устройству, устройство принадлежит человеку
		    SELECT DISTINCT k.chat_id, d.user_id
		    FROM personal_message_keys k JOIN devices d ON d.device_id = k.recipient
		),
		archived AS (
		    SELECT chat_id, user_id FROM chat_states
		    WHERE archived_at IS NOT NULL AND archived_at < now() - make_interval(days => $1)
		)
		SELECT p.chat_id
		FROM participants p
		GROUP BY p.chat_id
		-- Все участники чата присутствуют среди «убравших в архив» достаточно давно
		HAVING count(*) = count(*) FILTER (
		    WHERE EXISTS (SELECT 1 FROM archived a WHERE a.chat_id = p.chat_id AND a.user_id = p.user_id)
		)
		LIMIT $2`, days, limit)
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

// ── Аккаунт без номера и почты ──

// StartCredentialsWindow ставит срок, до которого можно входить без телефона и
// почты. Вызывается при заведении аккаунта без данных и когда номер забрал новый
// владелец: аккаунт и переписка остаются, но номер надо привязать новый.
func (s *Store) StartCredentialsWindow(ctx context.Context, personID string, days int) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE persons SET credentials_due = now() + make_interval(days => $2)
		WHERE person_id = $1 AND credentials_due IS NULL`, personID, days)
	return err
}

// ClearCredentialsWindow снимает срок: данные привязаны.
func (s *Store) ClearCredentialsWindow(ctx context.Context, personID string) error {
	_, err := s.pool.Exec(ctx,
		`UPDATE persons SET credentials_due = NULL WHERE person_id = $1`, personID)
	return err
}

// ErrCredentialsRequired — срок вышел, вход только после привязки телефона или почты.
var ErrCredentialsRequired = errors.New("нужно привязать телефон или почту")

// CheckCredentialsWindow — можно ли ещё входить без данных.
func (s *Store) CheckCredentialsWindow(ctx context.Context, personID string, now time.Time) error {
	var due *time.Time
	err := s.pool.QueryRow(ctx,
		`SELECT credentials_due FROM persons WHERE person_id = $1`, personID).Scan(&due)
	if errors.Is(err, pgx.ErrNoRows) {
		return ErrUserUnknown
	}
	if err != nil {
		return err
	}
	if due != nil && now.After(*due) {
		return ErrCredentialsRequired
	}
	return nil
}

// TakePhoneFromPerson отбирает номер у аккаунта и запускает срок на привязку нового.
//
// Так номер достаётся тому, кто может подтвердить владение им сегодня: оператор
// перевыпустил его другому человеку. Прежний владелец не теряет ни аккаунт, ни
// переписку — он теряет только номер.
func (s *Store) TakePhoneFromPerson(ctx context.Context, personID string, days int) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op

	if _, err := tx.Exec(ctx, `
		UPDATE persons SET phone_bidx = NULL, phone_enc = NULL,
		    credentials_due = now() + make_interval(days => $2)
		WHERE person_id = $1`, personID, days); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
