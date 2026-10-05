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

// SaveMessageBackups кладёт обёртки копии ключей владельца под эпоху копии (М2). Обёртка
// прежней эпохи заменяется новой: так копия переезжает на новую пару (М5).
func (s *Store) SaveMessageBackups(ctx context.Context, chatID, ownerID string, epoch int, items []MessageBackup) error {
	batch := &pgx.Batch{}
	for _, it := range items {
		batch.Queue(`
			INSERT INTO personal_message_backup (chat_id, message_id, owner_id, wrapped, epoch)
			VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (chat_id, message_id, owner_id) DO UPDATE
			   SET wrapped = EXCLUDED.wrapped, epoch = EXCLUDED.epoch
			 WHERE personal_message_backup.epoch < EXCLUDED.epoch`,
			chatID, it.MessageID, ownerID, it.Wrapped, epoch)
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

// ListMessageBackups — страница копии владельца в эпохе: сами сообщения (для конверта) и
// обёртки копии (М3). Удалённые и стёртые по сроку не отдаются — открывать там нечего.
func (s *Store) ListMessageBackups(ctx context.Context, chatID, ownerID string, epoch int, before uint64, limit int) ([]StoredMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	if before == 0 {
		before = ^uint64(0) >> 1
	}
	rows, err := s.pool.Query(ctx, `
		SELECT m.chat_id, m.message_id, m.client_msg_id, m.sender_id, m.sender_device, m.kind,
		       m.created_at_unix_ms, m.reply_to, m.format_version, m.encrypted_payload,
		       m.escrow_mlkem_ct, m.escrow_wrapped_key, m.escrow_key_version,
		       m.sender_ephemeral_pub, COALESCE(m.ratchet_envelope, ''::bytea), m.signature, COALESCE(m.key_commitment, ''::bytea),
		       b.wrapped
		FROM personal_message_backup b
		JOIN personal_messages m ON m.chat_id = b.chat_id AND m.message_id = b.message_id
		WHERE b.chat_id = $1 AND b.owner_id = $2 AND b.epoch = $3 AND b.message_id < $4
		  AND NOT m.deleted AND octet_length(m.encrypted_payload) > 0
		ORDER BY b.message_id DESC
		LIMIT $5`, chatID, ownerID, epoch, before, limit)
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
			&sm.WrappedKeyForDevice,
		); err != nil {
			return nil, err
		}
		out = append(out, sm)
	}
	return out, rows.Err()
}

// KeyCopyKey — открытый ключ копии личности (М1).
type KeyCopyKey struct {
	Epoch int
	Pub   []byte
	Sig   []byte
	// RotationDue — после отключения устройства пару пора сменить (М5).
	RotationDue bool
}

// ErrKeyCopyMissing — личность ещё не публиковала ключ копии.
var ErrKeyCopyMissing = errors.New("ключ копии не опубликован")

// ErrKeyCopyStale — публикуемая эпоха не новее действующей.
var ErrKeyCopyStale = errors.New("эпоха копии не новее действующей")

// KeyCopy — открытый ключ копии личности.
func (s *Store) KeyCopy(ctx context.Context, userID string) (KeyCopyKey, error) {
	var k KeyCopyKey
	err := s.pool.QueryRow(ctx, `SELECT epoch, pub, sig, rotation_due FROM key_copy WHERE user_id = $1`, userID).
		Scan(&k.Epoch, &k.Pub, &k.Sig, &k.RotationDue)
	if errors.Is(err, pgx.ErrNoRows) {
		return KeyCopyKey{}, ErrKeyCopyMissing
	}
	return k, err
}

// SetKeyCopy публикует открытый ключ копии. Эпоха только растёт: прежнюю пару вернуть нельзя,
// иначе после отключения украденного устройства (М5) копия снова пополнялась бы под ключ,
// который оно унесло. Повтор той же эпохи с тем же ключом — не ошибка.
func (s *Store) SetKeyCopy(ctx context.Context, userID string, k KeyCopyKey) error {
	tag, err := s.pool.Exec(ctx, `
		INSERT INTO key_copy (user_id, epoch, pub, sig) VALUES ($1, $2, $3, $4)
		ON CONFLICT (user_id) DO UPDATE SET epoch = EXCLUDED.epoch, pub = EXCLUDED.pub,
		       sig = EXCLUDED.sig, updated_at = now(),
		       rotation_due = key_copy.rotation_due AND key_copy.epoch = EXCLUDED.epoch
		 WHERE key_copy.epoch < EXCLUDED.epoch
		    OR (key_copy.epoch = EXCLUDED.epoch AND key_copy.pub = EXCLUDED.pub)`,
		userID, k.Epoch, k.Pub, k.Sig)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrKeyCopyStale
	}
	return nil
}

// GroupKeyCopy — обёртка одной версии ключа группы в копии.
type GroupKeyCopy struct {
	GroupID   string
	GKVersion int32
	Wrapped   []byte
}

// SaveGroupKeyCopies кладёт версии ключей групп в копию владельца (М2).
func (s *Store) SaveGroupKeyCopies(ctx context.Context, ownerID string, epoch int, items []GroupKeyCopy) error {
	batch := &pgx.Batch{}
	for _, it := range items {
		batch.Queue(`
			INSERT INTO group_key_copy (owner_id, group_id, gk_version, epoch, wrapped)
			VALUES ($1, $2, $3, $4, $5)
			ON CONFLICT (owner_id, group_id, gk_version, epoch) DO NOTHING`,
			ownerID, it.GroupID, it.GKVersion, epoch, it.Wrapped)
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

// ListGroupKeyCopies — все версии ключей групп в копии владельца в эпохе (М3).
func (s *Store) ListGroupKeyCopies(ctx context.Context, ownerID string, epoch int) ([]GroupKeyCopy, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT group_id::text, gk_version, wrapped FROM group_key_copy
		WHERE owner_id = $1 AND epoch = $2 ORDER BY group_id, gk_version`, ownerID, epoch)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []GroupKeyCopy
	for rows.Next() {
		var g GroupKeyCopy
		if err := rows.Scan(&g.GroupID, &g.GKVersion, &g.Wrapped); err != nil {
			return nil, err
		}
		out = append(out, g)
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
