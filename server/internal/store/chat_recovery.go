// Восстановление истории личной переписки: копии и ключи от помощников.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

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

// PersonalChatRef — личная переписка, в которой участвует личность: с кем и докуда.
type PersonalChatRef struct {
	ChatID        string
	PeerID        string
	LastMessageID uint64
}

// PersonalChatsOf — личные переписки личности (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ИУ1): новое устройство
// узнаёт, какие у него переписки и с кем. Участие выводится так же, как в
// IsChatParticipant, — по отправленным и по обёрткам ключей на свои устройства. Собеседник —
// другая сторона; переписка с самим собой отдаётся с собеседником-собой.
func (s *Store) PersonalChatsOf(ctx context.Context, userID string) ([]PersonalChatRef, error) {
	rows, err := s.pool.Query(ctx, `
		WITH mine AS (
		  SELECT DISTINCT k.chat_id FROM personal_message_keys k
		    JOIN devices d ON d.device_id = k.recipient WHERE d.user_id = $1
		  UNION
		  SELECT DISTINCT chat_id FROM personal_messages WHERE sender_id = $1
		)
		SELECT c.chat_id::text,
		  COALESCE(
		    (SELECT m.sender_id::text FROM personal_messages m WHERE m.chat_id = c.chat_id AND m.sender_id <> $1 LIMIT 1),
		    (SELECT d.user_id::text FROM personal_message_keys k JOIN devices d ON d.device_id = k.recipient
		       WHERE k.chat_id = c.chat_id AND d.user_id <> $1 LIMIT 1),
		    $1::text),
		  COALESCE((SELECT max(m.message_id) FROM personal_messages m WHERE m.chat_id = c.chat_id AND NOT m.deleted), 0)
		FROM mine c`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PersonalChatRef
	for rows.Next() {
		var r PersonalChatRef
		var last int64
		if err := rows.Scan(&r.ChatID, &r.PeerID, &last); err != nil {
			return nil, err
		}
		r.LastMessageID = uint64(last)
		out = append(out, r)
	}
	return out, rows.Err()
}
