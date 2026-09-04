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

// ErrGroupMessageNotFound — сообщения нет в этой группе (или оно удалено).
var ErrGroupMessageNotFound = errors.New("сообщение не найдено в группе")

// ErrGroupMessageForeign — сообщение чужое, а права на чужие у просящего нет.
var ErrGroupMessageForeign = errors.New("уровень чужого сообщения меняет админ")

// ErrGroupMessageNotNarrowed — уровень не сужен: он уже такой же или уже.
// Отдельная ошибка, а не «не найдено»: расширение доступа должно быть видно как отказ,
// а не как исчезновение сообщения.
var ErrGroupMessageNotNarrowed = errors.New("уровень сообщения не сужен")

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
	// Level — кому сервер отдаёт сообщение (ADR-0019): -1 шифр, 0 всем и всегда,
	// 1 всем, 2 вступившим, 3 по разрешению. В подпись НЕ входит: подпись неизменна,
	// а уровень по замыслу сужается после отправки.
	Level int16
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
			gk_version, payload, thread_root, reply_to, created_at_unix_ms, signature, level)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
		ON CONFLICT (group_id, client_msg_id) DO NOTHING
		RETURNING message_id`,
		m.GroupID, m.ClientMsgID, m.SenderID, m.SenderDevice, m.Kind,
		nullIfZero(m.GKVersion), m.Payload, nullIfZero(m.ThreadRoot), nullIfZero(m.ReplyTo),
		m.CreatedAtUnixMs, m.Signature, m.Level).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) { // конфликт — сообщение уже принято
		err = s.pool.QueryRow(ctx,
			`SELECT message_id FROM group_messages WHERE group_id = $1 AND client_msg_id = $2`,
			m.GroupID, m.ClientMsgID).Scan(&id)
		return id, true, err
	}
	return id, false, err
}

// ListGroupMessages — история группы (новые → старые); threadRoot > 0 — только ветка.
//
// maxLevel — граница выдачи (ADR-0019): отдаются сообщения с `level <= maxLevel`.
// Считает её вызывающий по правам просящего, а не хранилище: право — предмет API,
// а не SQL. Шифр (-1) проходит эту границу всегда, и это верно: расшифровать его
// сервер не может, а участник без ключа увидит непрозрачные байты.
func (s *Store) ListGroupMessages(ctx context.Context, groupID string, threadRoot, before int64, limit int, maxLevel int16) ([]GroupMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 50
	}
	if before <= 0 {
		before = int64(^uint64(0) >> 1) // max int64
	}
	rows, err := s.pool.Query(ctx, `
		SELECT message_id, sender_id, sender_device, kind, COALESCE(gk_version, 0), payload,
		       COALESCE(thread_root, 0), COALESCE(reply_to, 0), created_at_unix_ms, signature, level
		FROM group_messages
		WHERE group_id = $1 AND message_id < $2 AND NOT deleted
		  AND ($3::bigint = 0 OR thread_root = $3)
		  AND level <= $5
		ORDER BY message_id DESC
		LIMIT $4`, groupID, before, threadRoot, limit, maxLevel)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []GroupMessage
	for rows.Next() {
		m := GroupMessage{GroupID: groupID}
		if err := rows.Scan(&m.MessageID, &m.SenderID, &m.SenderDevice, &m.Kind, &m.GKVersion,
			&m.Payload, &m.ThreadRoot, &m.ReplyTo, &m.CreatedAtUnixMs, &m.Signature, &m.Level); err != nil {
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
// NarrowGroupMessageLevel сужает круг сообщения: ставит уровень строго БОЛЬШЕ нынешнего.
//
// Один метод, а не три (прочитать автора, проверить право, записать): архитектурный тест
// держит число методов хранилища и требует, чтобы потребитель объявлял узкий интерфейс.
// Здесь это ещё и правильнее по сути — проверка и запись идут в одной транзакции под
// `FOR UPDATE`, поэтому два админа, сужающие одно сообщение одновременно, не смогут
// записать меньший уровень поверх большего, то есть расширить доступ (ADR-0019 §6).
//
// allowForeign — можно ли трогать чужое сообщение; решает это вызывающий по роли.
// Возвращает автора: ему уходит оповещение о сужении.
func (s *Store) NarrowGroupMessageLevel(
	ctx context.Context, groupID string, messageID int64, level int16,
	requesterID string, allowForeign bool,
) (senderID string, err error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var current int16
	err = tx.QueryRow(ctx,
		`SELECT sender_id, level FROM group_messages
		  WHERE group_id = $1 AND message_id = $2 AND NOT deleted
		  FOR UPDATE`,
		groupID, messageID).Scan(&senderID, &current)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrGroupMessageNotFound
	} else if err != nil {
		return "", err
	}
	if senderID != requesterID && !allowForeign {
		return senderID, ErrGroupMessageForeign
	}
	if level <= current {
		return senderID, ErrGroupMessageNotNarrowed
	}
	if _, err = tx.Exec(ctx,
		`UPDATE group_messages SET level = $3 WHERE group_id = $1 AND message_id = $2`,
		groupID, messageID, level); err != nil {
		return senderID, err
	}
	return senderID, tx.Commit(ctx)
}

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
