// Одноразовые коды входа: сохранение и погашение.
//
// Код гасится ПРИ ПРОВЕРКЕ, а не при регистрации: повторный вызов с тем же
// кодом обязан отказать, иначе перехваченный код годился бы дважды.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ── Auth: SMS-коды и пользователи ──
// SaveSmsCode кладёт hash одноразового кода. Сам номер — шифртекстом: он нужен
// обратно при проверке кода, поэтому одним индексом не обойтись.
func (s *Store) SaveSmsCode(ctx context.Context, requestID, phone string, codeHash []byte, ttl time.Duration) error {
	enc, err := s.pii.Seal(phone)
	if err != nil {
		return err
	}
	_, err = s.pool.Exec(ctx, `
		INSERT INTO sms_codes (request_id, phone_bidx, phone_enc, code_hash, expires_at)
		VALUES ($1, $2, $3, $4, now() + $5)`,
		requestID, s.pii.BlindIndex(phone), enc, codeHash, ttl)
	return err
}

var ErrCodeInvalid = errors.New("код неверен, просрочен или уже использован")

// ConsumeSmsCode атомарно гасит код и возвращает телефон.
func (s *Store) ConsumeSmsCode(ctx context.Context, requestID string, codeHash []byte) (string, error) {
	var enc []byte
	err := s.pool.QueryRow(ctx, `
		UPDATE sms_codes SET used = TRUE
		WHERE request_id = $1 AND code_hash = $2 AND NOT used AND expires_at > now()
		RETURNING phone_enc`, requestID, codeHash).Scan(&enc)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrCodeInvalid
	}
	if err != nil {
		return "", err
	}
	return s.pii.Open(enc)
}
