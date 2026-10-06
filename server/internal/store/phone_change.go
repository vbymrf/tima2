package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"

	"tima/server/internal/pii"
)

// Смена SIM — смена номера аккаунта (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ9, Р34, Р40; порядок —
// ответ заказчика 2026-10-06). Заявка — фраза и код на прежний номер; через срок ожидания окно
// подтверждения — фраза той же личности и код на новый номер. Личность смена не меняет.

// PhoneChange — открытая заявка на смену номера.
type PhoneChange struct {
	ID            string
	PersonID      string
	UserID        string // личность, подавшая заявку: подтверждает только она
	NewPhone      string
	StartedAt     time.Time
	WindowFrom    time.Time
	WindowTo      time.Time
	WindowNoticed bool
}

// Исходы заявки.
const (
	PhoneChanged         = "changed"
	PhoneChangeExpired   = "expired"
	PhoneChangeCancelled = "cancelled"
)

// ErrNoPhoneChange — открытой заявки нет (или подтверждение вне окна).
var ErrNoPhoneChange = errors.New("заявки на смену номера нет")

// ErrPhoneChangeOpen — у аккаунта уже есть открытая заявка.
var ErrPhoneChangeOpen = errors.New("заявка на смену номера уже есть")

// ErrPhoneTaken — новый номер занят другим аккаунтом.
var ErrPhoneTaken = errors.New("номер занят другим аккаунтом")

const phoneChangeColumns = `id, person_id, user_id, new_phone_enc, started_at, window_from, window_to, window_noticed`

// scanPhoneChange — строка заявки; новый номер расшифровывается.
func scanPhoneChange(c *pii.Cipher, row pgx.Row) (PhoneChange, error) {
	var p PhoneChange
	var enc []byte
	err := row.Scan(&p.ID, &p.PersonID, &p.UserID, &enc, &p.StartedAt, &p.WindowFrom, &p.WindowTo, &p.WindowNoticed)
	if errors.Is(err, pgx.ErrNoRows) {
		return PhoneChange{}, ErrNoPhoneChange
	}
	if err != nil {
		return PhoneChange{}, err
	}
	p.NewPhone, err = c.Open(enc)
	return p, err
}

// StartPhoneChange заводит заявку: окно подтверждения — через wait, длиной window. Номер
// занят другим аккаунтом — ErrPhoneTaken; заявка уже есть — ErrPhoneChangeOpen.
func (s *Store) StartPhoneChange(ctx context.Context, userID, newPhone string, wait, window time.Duration) (PhoneChange, error) {
	enc, err := s.pii.Seal(newPhone)
	if err != nil {
		return PhoneChange{}, err
	}
	bidx := s.pii.BlindIndex(newPhone)
	var taken bool
	if err := s.pool.QueryRow(ctx, `
		SELECT EXISTS(SELECT 1 FROM persons WHERE phone_bidx = $1 AND state <> 'archived')`, bidx).Scan(&taken); err != nil {
		return PhoneChange{}, err
	}
	if taken {
		return PhoneChange{}, ErrPhoneTaken
	}
	from := time.Now().Add(wait)
	p, err := scanPhoneChange(s.pii, s.pool.QueryRow(ctx, `
		INSERT INTO phone_changes (person_id, user_id, new_phone_bidx, new_phone_enc, window_from, window_to)
		VALUES ((SELECT person_id FROM users WHERE user_id = $1), $1, $2, $3, $4, $5)
		RETURNING `+phoneChangeColumns, userID, bidx, enc, from, from.Add(window)))
	if err != nil && isUniqueViolation(err) {
		return PhoneChange{}, ErrPhoneChangeOpen
	}
	return p, err
}

// PhoneChangeOfUser — открытая заявка аккаунта, которому принадлежит личность.
func (s *Store) PhoneChangeOfUser(ctx context.Context, userID string) (PhoneChange, error) {
	return scanPhoneChange(s.pii, s.pool.QueryRow(ctx, `
		SELECT `+phoneChangeColumns+` FROM phone_changes
		WHERE closed_at IS NULL AND person_id = (SELECT person_id FROM users WHERE user_id = $1)`, userID))
}

// ConfirmPhoneChange — подтверждение в окне: номер аккаунта меняется на новый, заявка
// закрывается. Вне окна — ErrNoPhoneChange; номер успели занять — ErrPhoneTaken.
func (s *Store) ConfirmPhoneChange(ctx context.Context, id string) (PhoneChange, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return PhoneChange{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op
	p, err := scanPhoneChange(s.pii, tx.QueryRow(ctx, `
		UPDATE phone_changes SET outcome = 'changed', closed_at = now()
		WHERE id = $1 AND closed_at IS NULL AND now() >= window_from AND now() < window_to
		RETURNING `+phoneChangeColumns, id))
	if err != nil {
		return PhoneChange{}, err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE persons SET phone_bidx = c.new_phone_bidx, phone_enc = c.new_phone_enc
		FROM phone_changes c WHERE c.id = $1 AND persons.person_id = c.person_id`, id); err != nil {
		if isUniqueViolation(err) {
			return PhoneChange{}, ErrPhoneTaken
		}
		return PhoneChange{}, err
	}
	return p, tx.Commit(ctx)
}

// ClosePhoneChange закрывает открытую заявку с исходом outcome (expired, cancelled).
func (s *Store) ClosePhoneChange(ctx context.Context, id, outcome string) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE phone_changes SET outcome = $2, closed_at = now() WHERE id = $1 AND closed_at IS NULL`, id, outcome)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrNoPhoneChange
	}
	return nil
}

// PhoneChangesDue — открытые заявки, у которых открылось окно (now >= window_from).
func (s *Store) PhoneChangesDue(ctx context.Context, now time.Time) ([]PhoneChange, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT `+phoneChangeColumns+` FROM phone_changes
		WHERE closed_at IS NULL AND window_from <= $1`, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PhoneChange
	for rows.Next() {
		p, err := scanPhoneChange(s.pii, rows)
		if err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// MarkPhoneChangeWindowNoticed — извещение «окно открылось» ушло.
func (s *Store) MarkPhoneChangeWindowNoticed(ctx context.Context, id string) error {
	_, err := s.pool.Exec(ctx, `UPDATE phone_changes SET window_noticed = TRUE WHERE id = $1`, id)
	return err
}
