// Личные сообщения: запись конверта и чтение истории.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5/pgconn"
)

var ErrDuplicate = errors.New("дубликат client_msg_id")

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
	KeyCommitment      []byte // обязательство по ключу (ADR-0013); nil в конвертах v1
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
			sender_ephemeral_pub, ratchet_envelope, signature, key_commitment
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,NULLIF($15,''::bytea),$16,NULLIF($17,''::bytea))`,
		m.ChatID, m.MessageID, m.ClientMsgID, m.SenderID, m.SenderDevice, m.Kind,
		m.CreatedAtUnixMs, m.ReplyTo, m.FormatVersion, m.EncryptedPayload,
		m.EscrowMlkemCt, m.EscrowWrappedKey, m.EscrowKeyVersion,
		m.SenderEphemeralPub, m.RatchetEnvelope, m.Signature, m.KeyCommitment)
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
		       m.sender_ephemeral_pub, COALESCE(m.ratchet_envelope, ''::bytea), m.signature, COALESCE(m.key_commitment, ''::bytea),
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
			&sm.SenderEphemeralPub, &sm.RatchetEnvelope, &sm.Signature, &sm.KeyCommitment,
			&sm.WrappedKeyForDevice, &sm.WrapEphemeral,
		); err != nil {
			return nil, err
		}
		out = append(out, sm)
	}
	return out, rows.Err()
}
