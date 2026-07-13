// Package store — доступ к PostgreSQL (pgx). Сервер хранит только ciphertext
// и обёртки; ключей и открытого текста здесь нет по построению.
package store

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Store struct {
	pool *pgxpool.Pool
}

func New(ctx context.Context, databaseURL string) (*Store, error) {
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("подключение к PostgreSQL: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping PostgreSQL: %w", err)
	}
	return &Store{pool: pool}, nil
}

func (s *Store) Close() { s.pool.Close() }

// Migrate применяет *.sql из fsys по порядку имён; выполненные помнит в schema_migrations.
func (s *Store) Migrate(ctx context.Context, fsys fs.FS) error {
	if _, err := s.pool.Exec(ctx,
		`CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	names, err := fs.Glob(fsys, "*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	for _, name := range names {
		var exists bool
		if err := s.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE name=$1)`, name).Scan(&exists); err != nil {
			return err
		}
		if exists {
			continue
		}
		sql, err := fs.ReadFile(fsys, name)
		if err != nil {
			return err
		}
		tx, err := s.pool.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, string(sql)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("миграция %s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO schema_migrations(name) VALUES($1)`, name); err != nil {
			_ = tx.Rollback(ctx)
			return err
		}
		if err := tx.Commit(ctx); err != nil {
			return err
		}
	}
	return nil
}

// ResetForTests очищает таблицы (только интеграционные тесты; в бою не вызывается).
func (s *Store) ResetForTests(ctx context.Context) error {
	_, err := s.pool.Exec(ctx, `TRUNCATE personal_messages, personal_message_keys, personal_message_backup, devices, users, sms_codes, media_objects, group_key_history, group_wrapped_keys, groups, memberships, group_messages, device_events, sync_cursors, gc_state, channels, channel_subscriptions, channel_posts, calls, voice_rooms`)
	return err
}

// ── Auth: SMS-коды и пользователи ──

// SaveSmsCode кладёт hash одноразового кода.
func (s *Store) SaveSmsCode(ctx context.Context, requestID, phone string, codeHash []byte, ttl time.Duration) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO sms_codes (request_id, phone, code_hash, expires_at)
		VALUES ($1, $2, $3, now() + $4)`, requestID, phone, codeHash, ttl)
	return err
}

var ErrCodeInvalid = errors.New("код неверен, просрочен или уже использован")

// ConsumeSmsCode атомарно гасит код и возвращает телефон.
func (s *Store) ConsumeSmsCode(ctx context.Context, requestID string, codeHash []byte) (string, error) {
	var phone string
	err := s.pool.QueryRow(ctx, `
		UPDATE sms_codes SET used = TRUE
		WHERE request_id = $1 AND code_hash = $2 AND NOT used AND expires_at > now()
		RETURNING phone`, requestID, codeHash).Scan(&phone)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrCodeInvalid
	}
	return phone, err
}

// UpsertUserByPhone возвращает user_id, создавая пользователя при первом входе.
func (s *Store) UpsertUserByPhone(ctx context.Context, phone string) (string, error) {
	var userID string
	err := s.pool.QueryRow(ctx, `
		INSERT INTO users (phone) VALUES ($1)
		ON CONFLICT (phone) DO UPDATE SET phone = EXCLUDED.phone
		RETURNING user_id`, phone).Scan(&userID)
	return userID, err
}

// FindUserByPhone — user_id по телефону; ErrUserUnknown, если не зарегистрирован.
func (s *Store) FindUserByPhone(ctx context.Context, phone string) (string, error) {
	var userID string
	err := s.pool.QueryRow(ctx, `SELECT user_id FROM users WHERE phone = $1`, phone).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrUserUnknown
	}
	return userID, err
}

// ErrIdentityMismatch — присланный ключ личности не совпал с установленным у аккаунта.
var ErrIdentityMismatch = errors.New("ключ личности не совпадает с установленным для аккаунта")

// SetOrCheckIdentity: если у пользователя ещё нет ключа личности — устанавливает
// присланный; если есть — требует точного совпадения (ADR-0010 §этап 3). Пустой
// identityPub — пользователь без фразы (устройство работает, восстановление недоступно).
func (s *Store) SetOrCheckIdentity(ctx context.Context, userID string, identityPub []byte) error {
	if len(identityPub) == 0 {
		return nil
	}
	var existing []byte
	if err := s.pool.QueryRow(ctx, `SELECT identity_pub FROM users WHERE user_id = $1`, userID).Scan(&existing); err != nil {
		return err
	}
	if existing == nil {
		_, err := s.pool.Exec(ctx, `UPDATE users SET identity_pub = $2 WHERE user_id = $1`, userID, identityPub)
		return err
	}
	if !bytes.Equal(existing, identityPub) {
		return ErrIdentityMismatch
	}
	return nil
}

// IdentityPub — ключ личности аккаунта устройства (nil, если не установлен).
func (s *Store) IdentityPub(ctx context.Context, userID string) ([]byte, error) {
	var pub []byte
	err := s.pool.QueryRow(ctx, `SELECT identity_pub FROM users WHERE user_id = $1`, userID).Scan(&pub)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserUnknown
	}
	return pub, err
}

// NewDevice регистрирует устройство пользователя, device_id назначает база.
func (s *Store) NewDevice(ctx context.Context, userID string, encryptionPub, signingPub []byte) (string, error) {
	var deviceID string
	err := s.pool.QueryRow(ctx, `
		INSERT INTO devices (user_id, encryption_pub, signing_pub)
		VALUES ($1, $2, $3) RETURNING device_id`, userID, encryptionPub, signingPub).Scan(&deviceID)
	return deviceID, err
}

// ListDevices — неотозванные устройства пользователя с публичными ключами
// (GET /keys/devices: отправителю — для обёрток, получателю — для проверки подписи).
func (s *Store) ListDevices(ctx context.Context, userID string) ([]Device, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT device_id, user_id, encryption_pub, signing_pub
		FROM devices WHERE user_id = $1 AND revoked_at IS NULL ORDER BY created_at`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Device
	for rows.Next() {
		var d Device
		if err := rows.Scan(&d.DeviceID, &d.UserID, &d.EncryptionPub, &d.SigningPub); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// ── Устройства ──

type Device struct {
	DeviceID      string
	UserID        string
	EncryptionPub []byte
	SigningPub    []byte
}

// SigningKey возвращает Ed25519-ключ неотозванного устройства пользователя.
func (s *Store) SigningKey(ctx context.Context, deviceID, userID string) ([]byte, error) {
	var key []byte
	err := s.pool.QueryRow(ctx, `
		SELECT signing_pub FROM devices
		WHERE device_id = $1 AND user_id = $2 AND revoked_at IS NULL`,
		deviceID, userID).Scan(&key)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrDeviceUnknown
	}
	return key, err
}

var (
	ErrDeviceUnknown = errors.New("устройство не зарегистрировано или отозвано")
	ErrDuplicate     = errors.New("дубликат client_msg_id")
)

// ── Сообщения ──

type Message struct {
	ChatID             string
	MessageID          uint64
	ClientMsgID        string
	SenderID           string
	SenderDevice       string
	Kind               int32
	CreatedAtUnixMs    int64
	ReplyTo            uint64
	FormatVersion      int32
	EncryptedPayload   []byte
	EscrowMlkemCt      []byte
	EscrowWrappedKey   []byte
	EscrowKeyVersion   int32
	SenderEphemeralPub []byte
	RatchetEnvelope    []byte
	Signature          []byte
	WrappedKeys        map[string][]byte // recipient (device_id/vu_id) → wrapped
}

// SaveMessage атомарно кладёт конверт и обёртки. Повтор client_msg_id → ErrDuplicate.
func (s *Store) SaveMessage(ctx context.Context, m Message) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck — no-op после Commit

	_, err = tx.Exec(ctx, `
		INSERT INTO personal_messages (
			chat_id, message_id, client_msg_id, sender_id, sender_device, kind,
			created_at_unix_ms, reply_to, format_version, encrypted_payload,
			escrow_mlkem_ct, escrow_wrapped_key, escrow_key_version,
			sender_ephemeral_pub, ratchet_envelope, signature
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,NULLIF($15,''::bytea),$16)`,
		m.ChatID, m.MessageID, m.ClientMsgID, m.SenderID, m.SenderDevice, m.Kind,
		m.CreatedAtUnixMs, m.ReplyTo, m.FormatVersion, m.EncryptedPayload,
		m.EscrowMlkemCt, m.EscrowWrappedKey, m.EscrowKeyVersion,
		m.SenderEphemeralPub, m.RatchetEnvelope, m.Signature)
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" { // unique_violation: дедуп или переиспользование message_id
			return ErrDuplicate
		}
		return err
	}
	for recipient, wrapped := range m.WrappedKeys {
		if _, err := tx.Exec(ctx, `
			INSERT INTO personal_message_keys (chat_id, message_id, recipient, wrapped)
			VALUES ($1,$2,$3,$4)`, m.ChatID, m.MessageID, recipient, wrapped); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// ── Групповые ключи ──

type GroupRotation struct {
	GroupID            string
	GKVersion          int32
	RotatedBy          string
	SenderEphemeralPub []byte
	EscrowMlkemCt      []byte
	EscrowWrappedKey   []byte
	EscrowKeyVersion   int32
	Reason             string
	WrappedKeys        map[string][]byte // recipient device_id/vu_id → wrapped_GK
}

var ErrVersionConflict = errors.New("gk_version не следует за текущей версией")

// SaveGroupRotation атомарно кладёт версию GK: строго current+1 (первая — 1).
func (s *Store) SaveGroupRotation(ctx context.Context, rot GroupRotation) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck — no-op после Commit

	// Гонку параллельных ротаций одной группы исключает advisory-блокировка транзакции
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock(hashtextextended($1, 0))`, rot.GroupID); err != nil {
		return err
	}
	var current int32
	if err := tx.QueryRow(ctx,
		`SELECT COALESCE(MAX(gk_version), 0) FROM group_key_history WHERE group_id = $1`,
		rot.GroupID).Scan(&current); err != nil {
		return err
	}
	if rot.GKVersion != current+1 {
		return fmt.Errorf("%w: текущая %d, предложена %d", ErrVersionConflict, current, rot.GKVersion)
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO group_key_history (group_id, gk_version, rotated_by, sender_ephemeral_pub,
			escrow_mlkem_ct, escrow_wrapped_key, escrow_key_version, reason)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)`,
		rot.GroupID, rot.GKVersion, rot.RotatedBy, rot.SenderEphemeralPub,
		rot.EscrowMlkemCt, rot.EscrowWrappedKey, rot.EscrowKeyVersion, rot.Reason); err != nil {
		return err
	}
	for recipient, wrapped := range rot.WrappedKeys {
		if _, err := tx.Exec(ctx, `
			INSERT INTO group_wrapped_keys (group_id, gk_version, recipient, wrapped)
			VALUES ($1,$2,$3,$4)`, rot.GroupID, rot.GKVersion, recipient, wrapped); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// DeviceGroupKey — wrapped_GK одной версии для конкретного устройства.
type DeviceGroupKey struct {
	GKVersion          int32
	SenderEphemeralPub []byte
	Wrapped            []byte
}

// MessageBackup — резервная обёртка ключа сообщения под backup_key владельца.
type MessageBackup struct {
	MessageID uint64
	Wrapped   []byte
}

// SaveMessageBackups кладёт резервные обёртки владельца (ADR-0010 §этап 4).
func (s *Store) SaveMessageBackups(ctx context.Context, chatID, ownerID string, items []MessageBackup) error {
	batch := &pgx.Batch{}
	for _, it := range items {
		batch.Queue(`
			INSERT INTO personal_message_backup (chat_id, message_id, owner_id, wrapped)
			VALUES ($1, $2, $3, $4)
			ON CONFLICT (chat_id, message_id, owner_id) DO NOTHING`,
			chatID, it.MessageID, ownerID, it.Wrapped)
	}
	br := s.pool.SendBatch(ctx, batch)
	defer br.Close()
	for range items {
		if _, err := br.Exec(); err != nil {
			return err
		}
	}
	return nil
}

// ListMessageBackups — резервные обёртки владельца для чата (новые → старые).
func (s *Store) ListMessageBackups(ctx context.Context, chatID, ownerID string) ([]MessageBackup, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT b.message_id, b.wrapped
		FROM personal_message_backup b
		JOIN personal_messages m ON m.chat_id = b.chat_id AND m.message_id = b.message_id AND NOT m.deleted
		WHERE b.chat_id = $1 AND b.owner_id = $2
		ORDER BY b.message_id DESC`, chatID, ownerID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MessageBackup
	for rows.Next() {
		var it MessageBackup
		if err := rows.Scan(&it.MessageID, &it.Wrapped); err != nil {
			return nil, err
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

// IsChatParticipant — участвовал ли пользователь в личном чате (отправитель ИЛИ
// адресат обёрток). Право на восстановление истории чата (ADR-0010 §этап 2).
func (s *Store) IsChatParticipant(ctx context.Context, chatID, userID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS (
		  SELECT 1 FROM personal_messages WHERE chat_id = $1 AND sender_id = $2
		  UNION ALL
		  SELECT 1 FROM personal_message_keys k
		    JOIN devices d ON d.device_id = k.recipient
		    WHERE k.chat_id = $1 AND d.user_id = $2
		  LIMIT 1)`, chatID, userID).Scan(&ok)
	return ok, err
}

// IsChatParticipantDevice — принадлежит ли устройство пользователю-участнику чата
// (получатель обёрток восстановления должен быть стороной чата, не чужим).
func (s *Store) IsChatParticipantDevice(ctx context.Context, chatID, deviceID string) (bool, error) {
	var userID string
	err := s.pool.QueryRow(ctx, `SELECT user_id FROM devices WHERE device_id = $1 AND revoked_at IS NULL`, deviceID).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return s.IsChatParticipant(ctx, chatID, userID)
}

// ChatHelper — устройство-помощник для восстановления личного чата.
type ChatHelper struct {
	DeviceID string
	Own      bool // принадлежит тому же пользователю, что и запросивший (свои — без согласия)
}

// ChatHelperDevices — устройства с обёртками сообщений чата (кроме requester);
// Own=true, если то же устройство-владелец, что у запросившего (свои устройства
// помогают без согласия; собеседник — с согласием, ADR-0010 §защита).
func (s *Store) ChatHelperDevices(ctx context.Context, chatID, requesterDevice, requesterUser string) ([]ChatHelper, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT DISTINCT k.recipient, (d.user_id = $3) AS own
		FROM personal_message_keys k
		JOIN devices d ON d.device_id = k.recipient AND d.revoked_at IS NULL
		WHERE k.chat_id = $1 AND k.recipient <> $2`, chatID, requesterDevice, requesterUser)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ChatHelper
	for rows.Next() {
		var h ChatHelper
		if err := rows.Scan(&h.DeviceID, &h.Own); err != nil {
			return nil, err
		}
		out = append(out, h)
	}
	return out, rows.Err()
}

// RecoveryMessageKey — обёртка ключа сообщения под устройство-получателя (от помощника).
type RecoveryMessageKey struct {
	MessageID          uint64
	SenderEphemeralPub []byte
	Wrapped            []byte
}

// SaveRecoveryMessageKeys кладёт обёртки в personal_message_keys для recipient
// (ON CONFLICT DO NOTHING — идемпотентно).
func (s *Store) SaveRecoveryMessageKeys(ctx context.Context, chatID, recipient string, keys []RecoveryMessageKey) error {
	batch := &pgx.Batch{}
	for _, k := range keys {
		batch.Queue(`
			INSERT INTO personal_message_keys (chat_id, message_id, recipient, wrapped, sender_ephemeral_pub)
			VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (chat_id, message_id, recipient) DO NOTHING`,
			chatID, k.MessageID, recipient, k.Wrapped, k.SenderEphemeralPub)
	}
	br := s.pool.SendBatch(ctx, batch)
	defer br.Close()
	for range keys {
		if _, err := br.Exec(); err != nil {
			return err
		}
	}
	return nil
}

// DeviceEncryptionPub — X25519-ключ устройства (помощнику для обёртки восстановления).
func (s *Store) DeviceEncryptionPub(ctx context.Context, deviceID string) ([]byte, error) {
	var key []byte
	err := s.pool.QueryRow(ctx, `
		SELECT encryption_pub FROM devices WHERE device_id = $1 AND revoked_at IS NULL`, deviceID).Scan(&key)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrDeviceUnknown
	}
	return key, err
}

// MissingGKVersions — версии GK группы, для которых у устройства НЕТ обёртки
// (история до входа устройства). Источник запроса восстановления (ADR-0010 §этап 1).
func (s *Store) MissingGKVersions(ctx context.Context, groupID, deviceID string) ([]int32, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT h.gk_version FROM group_key_history h
		WHERE h.group_id = $1
		  AND NOT EXISTS (
		    SELECT 1 FROM group_wrapped_keys w
		    WHERE w.group_id = h.group_id AND w.gk_version = h.gk_version AND w.recipient = $2)
		ORDER BY h.gk_version`, groupID, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int32
	for rows.Next() {
		var v int32
		if err := rows.Scan(&v); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// HelperDevices — устройства активных участников группы (кроме requester),
// у которых ЕСТЬ обёртка хотя бы одной из versions — кандидаты в помощники.
func (s *Store) HelperDevices(ctx context.Context, groupID, requester string, versions []int32) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT DISTINCT w.recipient
		FROM group_wrapped_keys w
		JOIN devices d ON d.device_id = w.recipient AND d.revoked_at IS NULL
		JOIN memberships m ON m.user_id = d.user_id
		  AND m.target_type = 'group' AND m.target_id = w.group_id AND m.left_at IS NULL
		WHERE w.group_id = $1 AND w.recipient <> $2 AND w.gk_version = ANY($3)`,
		groupID, requester, versions)
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

// IsGroupMemberDevice — принадлежит ли устройство активному участнику группы
// (проверка и для запросившего восстановление, и для получателя обёрток).
func (s *Store) IsGroupMemberDevice(ctx context.Context, groupID, deviceID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS (
		  SELECT 1 FROM devices d
		  JOIN memberships m ON m.user_id = d.user_id
		    AND m.target_type = 'group' AND m.target_id = $1 AND m.left_at IS NULL
		  WHERE d.device_id = $2 AND d.revoked_at IS NULL)`, groupID, deviceID).Scan(&ok)
	return ok, err
}

// RecoveryKey — обёртка GK версии для устройства-получателя, сделанная помощником.
type RecoveryKey struct {
	GKVersion          int32
	SenderEphemeralPub []byte
	Wrapped            []byte
}

// SaveRecoveryKeys кладёт обёртки восстановления в group_wrapped_keys для recipient.
// Существующие не трогаются (ON CONFLICT DO NOTHING) — восстановление идемпотентно.
func (s *Store) SaveRecoveryKeys(ctx context.Context, groupID, recipient string, keys []RecoveryKey) error {
	batch := &pgx.Batch{}
	for _, k := range keys {
		batch.Queue(`
			INSERT INTO group_wrapped_keys (group_id, gk_version, recipient, wrapped, sender_ephemeral_pub)
			VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (group_id, gk_version, recipient) DO NOTHING`,
			groupID, k.GKVersion, recipient, k.Wrapped, k.SenderEphemeralPub)
	}
	br := s.pool.SendBatch(ctx, batch)
	defer br.Close()
	for range keys {
		if _, err := br.Exec(); err != nil {
			return err
		}
	}
	return nil
}

// CurrentGKVersion — максимальная версия GK группы (0, если ротаций не было).
// Нужна клиенту-админу, чьё новое устройство ещё без обёрток: чтобы ротировать
// строго current+1, а не вслепую с 0 (иначе version_conflict).
func (s *Store) CurrentGKVersion(ctx context.Context, groupID string) (int32, error) {
	var v int32
	err := s.pool.QueryRow(ctx, `
		SELECT COALESCE(MAX(gk_version), 0) FROM group_key_history WHERE group_id = $1`, groupID).Scan(&v)
	return v, err
}

// ListGroupKeysForDevice — версии > sinceVersion, для которых у устройства есть обёртка
// (GET /groups/{id}/keys?since_version=). Исключённый участник новых версий не увидит.
func (s *Store) ListGroupKeysForDevice(ctx context.Context, groupID, deviceID string, sinceVersion int32) ([]DeviceGroupKey, error) {
	// COALESCE: обёртка восстановления несёт свой эфемерал (0008); обычная — из истории.
	rows, err := s.pool.Query(ctx, `
		SELECT h.gk_version, COALESCE(w.sender_ephemeral_pub, h.sender_ephemeral_pub), w.wrapped
		FROM group_key_history h
		JOIN group_wrapped_keys w
		  ON w.group_id = h.group_id AND w.gk_version = h.gk_version AND w.recipient = $2
		WHERE h.group_id = $1 AND h.gk_version > $3
		ORDER BY h.gk_version`, groupID, deviceID, sinceVersion)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceGroupKey
	for rows.Next() {
		var k DeviceGroupKey
		if err := rows.Scan(&k.GKVersion, &k.SenderEphemeralPub, &k.Wrapped); err != nil {
			return nil, err
		}
		out = append(out, k)
	}
	return out, rows.Err()
}

// ── Медиа ──

type Media struct {
	MediaID     string
	OwnerID     string
	StorageKey  string
	Mime        string
	SizeBytes   int64
	IsEncrypted bool
	ChunkCount  int32
	Status      string
}

// CreateMedia регистрирует pending-объект; storage_key достраивается по media_id.
func (s *Store) CreateMedia(ctx context.Context, m Media, contentHash []byte) (Media, error) {
	err := s.pool.QueryRow(ctx, `
		INSERT INTO media_objects (owner_id, storage_key, mime, size_bytes, is_encrypted, content_hash, chunk_count)
		VALUES ($1, '', $2, $3, $4, $5, $6)
		RETURNING media_id`,
		m.OwnerID, m.Mime, m.SizeBytes, m.IsEncrypted, contentHash, m.ChunkCount).Scan(&m.MediaID)
	if err != nil {
		return m, err
	}
	m.StorageKey = "media/" + m.MediaID
	m.Status = "pending"
	_, err = s.pool.Exec(ctx, `UPDATE media_objects SET storage_key = $2 WHERE media_id = $1`, m.MediaID, m.StorageKey)
	return m, err
}

var ErrMediaNotFound = errors.New("медиа не найдено")

// GetMedia — метаданные объекта.
func (s *Store) GetMedia(ctx context.Context, mediaID string) (Media, error) {
	var m Media
	err := s.pool.QueryRow(ctx, `
		SELECT media_id, owner_id, storage_key, mime, size_bytes, is_encrypted, chunk_count, status
		FROM media_objects WHERE media_id = $1`, mediaID).
		Scan(&m.MediaID, &m.OwnerID, &m.StorageKey, &m.Mime, &m.SizeBytes, &m.IsEncrypted, &m.ChunkCount, &m.Status)
	if errors.Is(err, pgx.ErrNoRows) {
		return m, ErrMediaNotFound
	}
	return m, err
}

// CompleteMedia фиксирует фактический размер и переводит в complete (только владелец).
func (s *Store) CompleteMedia(ctx context.Context, mediaID, ownerID string, actualSize int64) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE media_objects SET status = 'complete', size_bytes = $3
		WHERE media_id = $1 AND owner_id = $2 AND status = 'pending'`, mediaID, ownerID, actualSize)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrMediaNotFound
	}
	return nil
}

// FindMediaByHash — CAS-дедупликация (только публичное): complete-объект с тем же SHA-256.
func (s *Store) FindMediaByHash(ctx context.Context, contentHash []byte) (string, error) {
	var mediaID string
	err := s.pool.QueryRow(ctx, `
		SELECT media_id FROM media_objects
		WHERE content_hash = $1 AND status = 'complete'`, contentHash).Scan(&mediaID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrMediaNotFound
	}
	return mediaID, err
}

// StoredMessage — конверт для выдачи истории вместе с обёрткой запрашивающего устройства.
type StoredMessage struct {
	Message
	WrappedKeyForDevice []byte
	WrapEphemeral       []byte // эфемерал обёртки восстановления (nil → из sender_ephemeral_pub)
}

// ListMessages — история чата (новые → старые) с wrapped_key указанного устройства.
// before=0 → с самого нового. Soft-deleted не отдаются.
func (s *Store) ListMessages(ctx context.Context, chatID, deviceID string, before uint64, limit int) ([]StoredMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if before == 0 {
		before = ^uint64(0) >> 1 // max int64
	}
	rows, err := s.pool.Query(ctx, `
		SELECT m.chat_id, m.message_id, m.client_msg_id, m.sender_id, m.sender_device, m.kind,
		       m.created_at_unix_ms, m.reply_to, m.format_version, m.encrypted_payload,
		       m.escrow_mlkem_ct, m.escrow_wrapped_key, m.escrow_key_version,
		       m.sender_ephemeral_pub, COALESCE(m.ratchet_envelope, ''::bytea), m.signature,
		       k.wrapped, k.sender_ephemeral_pub
		FROM personal_messages m
		JOIN personal_message_keys k
		  ON k.chat_id = m.chat_id AND k.message_id = m.message_id AND k.recipient = $2
		WHERE m.chat_id = $1 AND m.message_id < $3 AND NOT m.deleted
		ORDER BY m.message_id DESC
		LIMIT $4`, chatID, deviceID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var out []StoredMessage
	for rows.Next() {
		var sm StoredMessage
		if err := rows.Scan(
			&sm.ChatID, &sm.MessageID, &sm.ClientMsgID, &sm.SenderID, &sm.SenderDevice, &sm.Kind,
			&sm.CreatedAtUnixMs, &sm.ReplyTo, &sm.FormatVersion, &sm.EncryptedPayload,
			&sm.EscrowMlkemCt, &sm.EscrowWrappedKey, &sm.EscrowKeyVersion,
			&sm.SenderEphemeralPub, &sm.RatchetEnvelope, &sm.Signature,
			&sm.WrappedKeyForDevice, &sm.WrapEphemeral,
		); err != nil {
			return nil, err
		}
		out = append(out, sm)
	}
	return out, rows.Err()
}
