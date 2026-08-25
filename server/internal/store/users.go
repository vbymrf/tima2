// Люди: заведение по номеру, имена, сверка контактов.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// UpsertUserByPhone возвращает user_id ТЕКУЩЕЙ личности аккаунта с этим номером,
// заводя аккаунт при первом входе. Открытого номера в запросах нет — только слепой
// индекс.
//
// Аккаунт и личность разделены (миграция 0019): номер принадлежит аккаунту, а
// пишет человек под текущей личностью. Поэтому «вход по номеру» — это поиск
// аккаунта и возврат его головы цепочки, а не создание пользователя.
func (s *Store) UpsertUserByPhone(ctx context.Context, phone string) (string, error) {
	enc, err := s.pii.Seal(phone)
	if err != nil {
		return "", err
	}
	bidx := s.pii.BlindIndex(phone)

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op

	var personID string
	// Предикат ON CONFLICT обязан ПОБУКВЕННО совпадать с предикатом частичного
	// индекса idx_persons_phone_bidx, иначе PostgreSQL не может вывести индекс и
	// падает. Условие `state <> 'archived'` здесь не украшение: архивный аккаунт
	// номер не держит, и регистрация на него заводит НОВЫЙ аккаунт, а не оживляет
	// прежний (ДОКУМЕНТАЦИЯ/04 §5).
	err = tx.QueryRow(ctx, `
		INSERT INTO persons (phone_bidx, phone_enc) VALUES ($1, $2)
		ON CONFLICT (phone_bidx) WHERE phone_bidx IS NOT NULL AND state <> 'archived'
		DO UPDATE SET phone_enc = EXCLUDED.phone_enc
		RETURNING person_id`, bidx, enc).Scan(&personID)
	if err != nil {
		return "", err
	}

	var userID string
	err = tx.QueryRow(ctx,
		`SELECT user_id FROM users WHERE person_id = $1 AND valid_to IS NULL`, personID).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		// Аккаунт только что заведён (или остался без текущей личности) — заводим голову цепочки.
		err = tx.QueryRow(ctx,
			`INSERT INTO users (person_id) VALUES ($1) RETURNING user_id`, personID).Scan(&userID)
	}
	if err != nil {
		return "", err
	}
	return userID, tx.Commit(ctx)
}

// SetDisplayName — своё публичное имя (показывается собеседникам вместо номера).
// Имя принадлежит аккаунту, поэтому едино для всей цепочки идентификаторов.
func (s *Store) SetDisplayName(ctx context.Context, userID, name string) error {
	enc, err := s.pii.Seal(name)
	if err != nil {
		return err
	}
	_, err = s.pool.Exec(ctx, `
		UPDATE persons SET name_enc = $2
		WHERE person_id = (SELECT person_id FROM users WHERE user_id = $1)`, userID, enc)
	return err
}

// PhonesOfChatPeers — телефоны тех из ids, с кем у userID есть личная переписка:
// он писал им (его сообщение адресовано их устройству) или они ему. Номер человека,
// с которым переписки нет, по его user_id узнать нельзя — id утекает в группах и каналах.
func (s *Store) PhonesOfChatPeers(ctx context.Context, userID string, ids []string) (map[string]string, error) {
	rows, err := s.pool.Query(ctx, `
		WITH peers AS (
		  SELECT m.sender_id AS peer                      -- они писали мне
		  FROM personal_messages m
		  JOIN personal_message_keys k ON k.chat_id = m.chat_id AND k.message_id = m.message_id
		  JOIN devices d ON d.device_id = k.recipient AND d.user_id = $1
		  WHERE m.sender_id = ANY($2)
		  UNION
		  SELECT d.user_id AS peer                        -- я писал им
		  FROM personal_messages m
		  JOIN personal_message_keys k ON k.chat_id = m.chat_id AND k.message_id = m.message_id
		  JOIN devices d ON d.device_id = k.recipient
		  WHERE m.sender_id = $1 AND d.user_id = ANY($2)
		)
		SELECT u.user_id, pr.phone_enc
		FROM users u
		JOIN peers p   ON p.peer = u.user_id
		JOIN persons pr ON pr.person_id = u.person_id`, userID, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string]string)
	for rows.Next() {
		var id string
		var enc []byte
		if err := rows.Scan(&id, &enc); err != nil {
			return nil, err
		}
		phone, err := s.pii.Open(enc)
		if err != nil {
			return nil, err
		}
		if phone != "" {
			out[id] = phone
		}
	}
	return out, rows.Err()
}

// DisplayNames — публичные имена по списку user_id (batch резолв id→имя для UI).
// Пустые имена (не заданы) в ответ не попадают.
func (s *Store) DisplayNames(ctx context.Context, ids []string) (map[string]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT u.user_id, p.name_enc
		FROM users u JOIN persons p ON p.person_id = u.person_id
		WHERE u.user_id = ANY($1) AND p.name_enc IS NOT NULL`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string]string)
	for rows.Next() {
		var id string
		var enc []byte
		if err := rows.Scan(&id, &enc); err != nil {
			return nil, err
		}
		name, err := s.pii.Open(enc)
		if err != nil {
			return nil, err
		}
		if name != "" {
			out[id] = name
		}
	}
	return out, rows.Err()
}

// FindUserByPhone — user_id по телефону; ErrUserUnknown, если не зарегистрирован.
func (s *Store) FindUserByPhone(ctx context.Context, phone string) (string, error) {
	var userID string
	err := s.pool.QueryRow(ctx, `
		SELECT u.user_id FROM users u
		JOIN persons p ON p.person_id = u.person_id
		WHERE p.phone_bidx = $1 AND u.valid_to IS NULL`, s.pii.BlindIndex(phone)).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrUserUnknown
	}
	return userID, err
}

// FindUsersByPhones — телефон→user_id для зарегистрированных (contact discovery, батч).
// В базу уходят только слепые индексы; обратное сопоставление делаем у себя.
func (s *Store) FindUsersByPhones(ctx context.Context, phones []string) (map[string]string, error) {
	out := make(map[string]string, len(phones))
	if len(phones) == 0 {
		return out, nil
	}
	byIndex := make(map[string]string, len(phones)) // hex(bidx) → исходный номер
	idx := make([][]byte, 0, len(phones))
	for _, p := range phones {
		b := s.pii.BlindIndex(p)
		byIndex[string(b)] = p
		idx = append(idx, b)
	}
	rows, err := s.pool.Query(ctx,
		`SELECT p.phone_bidx, u.user_id FROM users u
		 JOIN persons p ON p.person_id = u.person_id
		 WHERE p.phone_bidx = ANY($1) AND u.valid_to IS NULL`, idx)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var bidx []byte
		var userID string
		if err := rows.Scan(&bidx, &userID); err != nil {
			return nil, err
		}
		if phone, ok := byIndex[string(bidx)]; ok {
			out[phone] = userID
		}
	}
	return out, rows.Err()
}
