// Привязка нового устройства по QR (key-lifecycle.md §2). Три шага: NewDevice
// уже умеет завести устройство по прямому доверию (SMS-регистрация); здесь то
// же самое, но доверие приходит не от SMS, а от уже авторизованного устройства,
// подтвердившего QR (api.linkConfirm проверяет его подпись до вызова ConfirmLinkSession).
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ErrLinkSessionInvalid — сессия привязки не найдена, просрочена, уже
// подтверждена/использована, либо секрет не совпал. Один код на все случаи:
// новому устройству, ожидающему confirm, не нужно различать «ещё рано» от
// «токен неверный» — оно всё равно просто продолжает опрашивать до истечения
// собственного таймера (expires_at, который оно уже получило от /link/start).
var ErrLinkSessionInvalid = errors.New("сессия привязки не найдена, просрочена или уже использована")

// LinkSession — то, что подтверждающее устройство должно увидеть, чтобы
// проверить подпись над данными нового устройства.
type LinkSession struct {
	SessionID     string
	EncryptionPub []byte
	SigningPub    []byte
	DeviceName    string
	SecretHash    []byte
}

// CreateLinkSession — новое устройство запросило вход по QR (аккаунта у него ещё нет).
func (s *Store) CreateLinkSession(
	ctx context.Context,
	encryptionPub, signingPub []byte,
	deviceName string,
	secretHash, claimTokenHash []byte,
	expiresAt time.Time,
) (string, error) {
	var sessionID string
	err := s.pool.QueryRow(ctx, `
		INSERT INTO device_link_sessions
			(encryption_pub, signing_pub, device_name, secret_hash, claim_token_hash, expires_at)
		VALUES ($1, $2, $3, $4, $5, $6) RETURNING session_id`,
		encryptionPub, signingPub, deviceName, secretHash, claimTokenHash, expiresAt).Scan(&sessionID)
	return sessionID, err
}

// GetLinkSessionForConfirm — сохранённые ключи/имя нового устройства по session_id,
// нужны api-слою, чтобы собрать те же канонические байты, которые подписало
// подтверждающее устройство, и проверить подпись ДО того, как менять состояние.
func (s *Store) GetLinkSessionForConfirm(ctx context.Context, sessionID string) (LinkSession, error) {
	var ls LinkSession
	err := s.pool.QueryRow(ctx, `
		SELECT session_id, encryption_pub, signing_pub, device_name, secret_hash
		FROM device_link_sessions
		WHERE session_id = $1 AND expires_at > now() AND confirmed_at IS NULL`, sessionID).
		Scan(&ls.SessionID, &ls.EncryptionPub, &ls.SigningPub, &ls.DeviceName, &ls.SecretHash)
	if errors.Is(err, pgx.ErrNoRows) {
		return LinkSession{}, ErrLinkSessionInvalid
	}
	return ls, err
}

// ConfirmLinkSession — подпись уже проверена api-слоем; здесь — атомарно завести
// устройство новому пользователю и закрыть сессию для повторного confirm.
// Перечитывает сессию FOR UPDATE: между GetLinkSessionForConfirm и этим вызовом
// её мог confirm'ить кто-то другой (два человека сканируют один QR одновременно) —
// вторая попытка должна проиграть, а не завести второе устройство.
func (s *Store) ConfirmLinkSession(ctx context.Context, sessionID, secretHash []byte, userID string) (deviceID string, err error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx)

	var encPub, sigPub []byte
	var deviceName string
	err = tx.QueryRow(ctx, `
		SELECT encryption_pub, signing_pub, device_name
		FROM device_link_sessions
		WHERE session_id = $1 AND secret_hash = $2 AND expires_at > now() AND confirmed_at IS NULL
		FOR UPDATE`, sessionID, secretHash).Scan(&encPub, &sigPub, &deviceName)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrLinkSessionInvalid
	} else if err != nil {
		return "", err
	}

	if err = tx.QueryRow(ctx, `
		INSERT INTO devices (user_id, encryption_pub, signing_pub, name)
		VALUES ($1, $2, $3, $4) RETURNING device_id`,
		userID, encPub, sigPub, deviceName).Scan(&deviceID); err != nil {
		return "", err
	}
	if _, err = tx.Exec(ctx, `
		UPDATE device_link_sessions
		SET user_id = $2, linked_device_id = $3, confirmed_at = now()
		WHERE session_id = $1`, sessionID, userID, deviceID); err != nil {
		return "", err
	}
	if err = tx.Commit(ctx); err != nil {
		return "", err
	}
	return deviceID, nil
}

// ClaimLinkSession — новое устройство обменивает claim_token на свою сессию,
// как только confirm случился. Одноразово: claimed_at гасит повторные попытки
// тем же токеном (сеть могла продублировать запрос при повторной отправке).
func (s *Store) ClaimLinkSession(ctx context.Context, sessionID string, claimTokenHash []byte) (userID, deviceID string, err error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", "", err
	}
	defer tx.Rollback(ctx)

	err = tx.QueryRow(ctx, `
		SELECT user_id, linked_device_id
		FROM device_link_sessions
		WHERE session_id = $1 AND claim_token_hash = $2 AND expires_at > now()
		  AND confirmed_at IS NOT NULL AND claimed_at IS NULL
		FOR UPDATE`, sessionID, claimTokenHash).Scan(&userID, &deviceID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", "", ErrLinkSessionInvalid
	} else if err != nil {
		return "", "", err
	}
	if _, err = tx.Exec(ctx, `
		UPDATE device_link_sessions SET claimed_at = now() WHERE session_id = $1`, sessionID); err != nil {
		return "", "", err
	}
	if err = tx.Commit(ctx); err != nil {
		return "", "", err
	}
	return userID, deviceID, nil
}
