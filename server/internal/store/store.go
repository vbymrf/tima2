// Package store — доступ к PostgreSQL (pgx). Сервер хранит только ciphertext
// и обёртки; ключей и открытого текста здесь нет по построению.
package store

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"sort"

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
	_, err := s.pool.Exec(ctx, `TRUNCATE personal_messages, personal_message_keys, devices`)
	return err
}

// ── Устройства ──

type Device struct {
	DeviceID      string
	UserID        string
	EncryptionPub []byte
	SigningPub    []byte
}

// RegisterDevice — upsert устройства. До появления Auth/User Service используется
// тестами и dev-эндпоинтом; в бою регистрация — через /auth (фаза Auth).
func (s *Store) RegisterDevice(ctx context.Context, d Device) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO devices (device_id, user_id, encryption_pub, signing_pub)
		VALUES ($1, $2, $3, $4)
		ON CONFLICT (device_id) DO UPDATE SET encryption_pub = $3, signing_pub = $4`,
		d.DeviceID, d.UserID, d.EncryptionPub, d.SigningPub)
	return err
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

// StoredMessage — конверт для выдачи истории вместе с обёрткой запрашивающего устройства.
type StoredMessage struct {
	Message
	WrappedKeyForDevice []byte
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
		       k.wrapped
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
			&sm.WrappedKeyForDevice,
		); err != nil {
			return nil, err
		}
		out = append(out, sm)
	}
	return out, rows.Err()
}
