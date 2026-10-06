package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// Доверие к устройствам (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ1–ДУ2): ключи подписи устройств (КПУ)
// аккаунта и свидетельства устройств. Подписи проверяет api — хранилище их только держит.

// SigningKey — КПУ аккаунта: открытый ключ и свидетельство ключом личности.
type SigningKey struct {
	AskID    string
	UserID   string
	DeviceID string
	Pub      []byte
	Sig      []byte
}

// ErrNoSigningKey — у телефона нет действующего КПУ (заведён до ДУ1 или фразу не вводили).
var ErrNoSigningKey = errors.New("у устройства нет действующего ключа подписи устройств")

// AddSigningKey заводит КПУ телефона. Прежний действующий КПУ того же телефона отзывается:
// у телефона один КПУ — тот, чей закрытый ключ у него сейчас.
func (s *Store) AddSigningKey(ctx context.Context, userID, deviceID string, pub, sig []byte) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op
	if _, err := tx.Exec(ctx, `
		UPDATE account_signing_keys SET revoked_at = now()
		WHERE device_id = $1 AND user_id = $2 AND revoked_at IS NULL`, deviceID, userID); err != nil {
		return "", err
	}
	var askID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO account_signing_keys (user_id, device_id, ask_pub, ask_sig)
		VALUES ($1, $2, $3, $4) RETURNING ask_id`, userID, deviceID, pub, sig).Scan(&askID); err != nil {
		return "", err
	}
	return askID, tx.Commit(ctx)
}

// ActiveSigningKeys — неотозванные КПУ аккаунта.
func (s *Store) ActiveSigningKeys(ctx context.Context, userID string) ([]SigningKey, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT ask_id, user_id, COALESCE(device_id::text, ''), ask_pub, ask_sig
		FROM account_signing_keys WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []SigningKey
	for rows.Next() {
		var k SigningKey
		if err := rows.Scan(&k.AskID, &k.UserID, &k.DeviceID, &k.Pub, &k.Sig); err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, rows.Err()
}

// DeviceSigningKey — действующий КПУ, который держит этот телефон.
func (s *Store) DeviceSigningKey(ctx context.Context, userID, deviceID string) (SigningKey, error) {
	var k SigningKey
	err := s.pool.QueryRow(ctx, `
		SELECT ask_id, user_id, COALESCE(device_id::text, ''), ask_pub, ask_sig
		FROM account_signing_keys
		WHERE user_id = $1 AND device_id = $2 AND revoked_at IS NULL
		ORDER BY created_at DESC LIMIT 1`, userID, deviceID).Scan(&k.AskID, &k.UserID, &k.DeviceID, &k.Pub, &k.Sig)
	if errors.Is(err, pgx.ErrNoRows) {
		return SigningKey{}, ErrNoSigningKey
	}
	return k, err
}

// SetDeviceCertificate записывает свидетельство неотозванного устройства аккаунта.
// by — "identity" или "ask"; askID нужен только для "ask".
func (s *Store) SetDeviceCertificate(ctx context.Context, userID, deviceID, by, askID string, sig []byte) error {
	var ask any
	if by == "ask" {
		ask = askID
	}
	ct, err := s.pool.Exec(ctx, `
		UPDATE devices SET cert_by = $3, cert_ask_id = $4, cert_sig = $5
		WHERE device_id = $1 AND user_id = $2 AND revoked_at IS NULL`, deviceID, userID, by, ask, sig)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrDeviceNotFound
	}
	return nil
}

// DeviceKeys — открытые ключи неотозванного устройства аккаунта (что подписывает свидетельство).
func (s *Store) DeviceKeys(ctx context.Context, userID, deviceID string) (enc, sig []byte, err error) {
	err = s.pool.QueryRow(ctx, `
		SELECT encryption_pub, signing_pub FROM devices
		WHERE device_id = $1 AND user_id = $2 AND revoked_at IS NULL`, deviceID, userID).Scan(&enc, &sig)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil, ErrDeviceNotFound
	}
	return enc, sig, err
}

// SetDeviceAttestation записывает итог аттестации устройства (ДУ8, режим «записывать»).
func (s *Store) SetDeviceAttestation(ctx context.Context, userID, deviceID, state, info string) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE devices SET attestation_state = $3, attestation_info = $4, attested_at = now()
		WHERE device_id = $1 AND user_id = $2 AND revoked_at IS NULL`, deviceID, userID, state, info)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrDeviceNotFound
	}
	return nil
}

// DemandAttestation ставит устройству требование пройти аттестацию (ПЛАН-(ЗБ)-ЗАЩИТЫ-ОТ-БОТОВ
// ЗБ1): до свежей годной аттестации сервер отказывает ему в действиях. Повторный вызов
// передвигает время требования — прежняя аттестация его уже не снимает.
//
// Кто вызывает — решается с системной защитой от ботов; сейчас это ручка для тестов и стенда.
// У ПК аттестации нет: требование к ПК — полная остановка устройства, а не проверка.
func (s *Store) DemandAttestation(ctx context.Context, deviceID, reason string) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE devices SET attestation_demanded_at = clock_timestamp(), attestation_demand_reason = $2
		WHERE device_id = $1 AND revoked_at IS NULL`, deviceID, reason)
	if err != nil {
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrDeviceNotFound
	}
	return nil
}

// DeviceAttested — прошло ли устройство аттестацию (состояние verified), когда бы то ни было.
func (s *Store) DeviceAttested(ctx context.Context, deviceID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `SELECT attestation_state = 'verified' FROM devices WHERE device_id = $1`, deviceID).Scan(&ok)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	return ok, err
}

// AttestationDemanded — стоит ли требование аттестации, не снятое свежей годной аттестацией (ЗБ1).
func (s *Store) AttestationDemanded(ctx context.Context, deviceID string) (bool, error) {
	var demanded bool
	err := s.pool.QueryRow(ctx, `
		SELECT attestation_demanded_at IS NOT NULL
		   AND (attestation_state <> 'verified' OR attested_at IS NULL OR attested_at < attestation_demanded_at)
		FROM devices WHERE device_id = $1`, deviceID).Scan(&demanded)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	return demanded, err
}

// CertifiedDevices — какие из устройств заверены действующей цепочкой (ДУ3): свидетельство
// ключом личности или неотозванным ключом подписи устройств.
func (s *Store) CertifiedDevices(ctx context.Context, deviceIDs []string) (map[string]bool, error) {
	out := make(map[string]bool, len(deviceIDs))
	if len(deviceIDs) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `
		SELECT d.device_id::text FROM devices d
		WHERE d.device_id::text = ANY($1) AND d.revoked_at IS NULL
		  AND (d.cert_by = 'identity'
		       OR (d.cert_by = 'ask' AND EXISTS (SELECT 1 FROM account_signing_keys k
		                                         WHERE k.ask_id = d.cert_ask_id AND k.revoked_at IS NULL)))`, deviceIDs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out[id] = true
	}
	return out, rows.Err()
}
