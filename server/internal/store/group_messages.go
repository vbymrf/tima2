// Сообщения групп (data-model.md §4). Сервер хранит payload как есть:
// private-группа — SecretBox(zstd(MessageBody), GK), публичная — plaintext
// protobuf; расшифровки нет по построению.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

type GroupMessage struct {
	MessageID       int64
	GroupID         string
	ClientMsgID     string
	SenderID        string
	SenderDevice    string
	Kind            int32
	GKVersion       int32 // 0 = публичная группа (в базе NULL)
	Payload         []byte
	ThreadRoot      int64 // 0 = вне ветки (в базе NULL)
	ReplyTo         int64
	CreatedAtUnixMs int64
	Signature       []byte
}

// nullIfZero — 0 в Go-структуре ↔ NULL в базе (gk_version, thread_root, reply_to).
func nullIfZero[T int32 | int64](v T) any {
	if v == 0 {
		return nil
	}
	return v
}

// SaveGroupMessage кладёт сообщение, message_id назначает база.
// Повтор client_msg_id → прежний message_id и duplicate=true.
func (s *Store) SaveGroupMessage(ctx context.Context, m GroupMessage) (int64, bool, error) {
	var id int64
	err := s.pool.QueryRow(ctx, `
		INSERT INTO group_messages (group_id, client_msg_id, sender_id, sender_device, kind,
			gk_version, payload, thread_root, reply_to, created_at_unix_ms, signature)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
		ON CONFLICT (group_id, client_msg_id) DO NOTHING
		RETURNING message_id`,
		m.GroupID, m.ClientMsgID, m.SenderID, m.SenderDevice, m.Kind,
		nullIfZero(m.GKVersion), m.Payload, nullIfZero(m.ThreadRoot), nullIfZero(m.ReplyTo),
		m.CreatedAtUnixMs, m.Signature).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) { // конфликт — сообщение уже принято
		err = s.pool.QueryRow(ctx,
			`SELECT message_id FROM group_messages WHERE group_id = $1 AND client_msg_id = $2`,
			m.GroupID, m.ClientMsgID).Scan(&id)
		return id, true, err
	}
	return id, false, err
}

// ListGroupMessages — история группы (новые → старые); threadRoot > 0 — только ветка.
func (s *Store) ListGroupMessages(ctx context.Context, groupID string, threadRoot, before int64, limit int) ([]GroupMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if before <= 0 {
		before = int64(^uint64(0) >> 1) // max int64
	}
	rows, err := s.pool.Query(ctx, `
		SELECT message_id, sender_id, sender_device, kind, COALESCE(gk_version, 0), payload,
		       COALESCE(thread_root, 0), COALESCE(reply_to, 0), created_at_unix_ms, signature
		FROM group_messages
		WHERE group_id = $1 AND message_id < $2 AND NOT deleted
		  AND ($3::bigint = 0 OR thread_root = $3)
		ORDER BY message_id DESC
		LIMIT $4`, groupID, before, threadRoot, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []GroupMessage
	for rows.Next() {
		m := GroupMessage{GroupID: groupID}
		if err := rows.Scan(&m.MessageID, &m.SenderID, &m.SenderDevice, &m.Kind, &m.GKVersion,
			&m.Payload, &m.ThreadRoot, &m.ReplyTo, &m.CreatedAtUnixMs, &m.Signature); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// GroupMessageExists — проверка reply_to/thread_root: сообщение этой группы.
func (s *Store) GroupMessageExists(ctx context.Context, groupID string, messageID int64) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM group_messages WHERE group_id = $1 AND message_id = $2)`,
		groupID, messageID).Scan(&ok)
	return ok, err
}

// GroupKeyVersionExists — есть ли такая версия GK в истории ротаций группы.
func (s *Store) GroupKeyVersionExists(ctx context.Context, groupID string, version int32) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM group_key_history WHERE group_id = $1 AND gk_version = $2)`,
		groupID, version).Scan(&ok)
	return ok, err
}

// SenderPostedWithin — писал ли отправитель в группу за последние seconds (slow mode).
func (s *Store) SenderPostedWithin(ctx context.Context, groupID, senderID string, seconds int32) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS(SELECT 1 FROM group_messages
			WHERE group_id = $1 AND sender_id = $2
			  AND created_at > now() - make_interval(secs => $3))`,
		groupID, senderID, seconds).Scan(&ok)
	return ok, err
}

// GroupMemberInfo — роль и бан активного участника (ErrNotMember, если не состоит).
func (s *Store) GroupMemberInfo(ctx context.Context, groupID, userID string) (string, *time.Time, error) {
	var role string
	var bannedUntil *time.Time
	err := s.pool.QueryRow(ctx, `
		SELECT role, banned_until FROM memberships
		WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`,
		groupID, userID).Scan(&role, &bannedUntil)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return "", nil, ErrNotMember
	}
	return role, bannedUntil, err
}

// ActiveMemberDevices — действующие устройства активных участников группы
// (адресаты live-доставки). exceptDevice — устройство отправителя.
func (s *Store) ActiveMemberDevices(ctx context.Context, groupID, exceptDevice string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT d.device_id FROM devices d
		JOIN memberships m ON m.target_type = 'group' AND m.target_id = $1
		     AND m.user_id = d.user_id AND m.left_at IS NULL
		WHERE d.revoked_at IS NULL AND d.device_id::text <> $2`,
		groupID, exceptDevice)
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
