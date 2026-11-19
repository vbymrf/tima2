// Переписка «сущность — человек» (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.9, СЦ15). Отдельным типом: у `*Store` бюджет
// методов (architecture_test).
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ErrThreadNotFound — ленты нет.
var ErrThreadNotFound = errors.New("ленты нет")

// Threads — ленты «сущность — человек».
type Threads struct{ s *Store }

// ThreadsOf — ленты поверх хранилища.
func ThreadsOf(s *Store) Threads { return Threads{s: s} }

// Thread — лента глазами любой стороны.
type Thread struct {
	ThreadID    string
	EntityKind  string
	EntityID    string
	UserID      string
	UserState   string
	UserRead    int64
	EntityRead  int64
	UserBlocked bool
	LastID      int64
	LastAt      time.Time
	// Для списков: название сущности, текст последнего, непрочитанного у просящей стороны.
	Title   string
	Preview string
	Unread  int64
}

// ThreadMessage — сообщение ленты. AuthorID — только для стороны сущности.
type ThreadMessage struct {
	MessageID int64
	Side      string
	AuthorID  string
	Body      []byte
	Preview   string
	CreatedAt time.Time
}

// TopLevel — сущность верхнего уровня (§1.9, 4): группа или канал внутри сообщества — само сообщество.
func (t Threads) TopLevel(ctx context.Context, kind, id string) (string, string, error) {
	if kind == "community" {
		return kind, id, nil
	}
	table, key := entityTable(kind)
	var community *string
	err := t.s.pool.QueryRow(ctx, `SELECT community_id::text FROM `+table+` WHERE `+key+` = $1 AND deleted_at IS NULL`, id).Scan(&community)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return "", "", ErrGroupNotFound
	}
	if err != nil {
		return "", "", err
	}
	if community != nil && *community != "" {
		return "community", *community, nil
	}
	return kind, id, nil
}

// Accepts — принимает ли сущность сообщения (§1.9, 3).
func (t Threads) Accepts(ctx context.Context, kind, id string) (bool, error) {
	table, key := entityTable(kind)
	var ok bool
	err := t.s.pool.QueryRow(ctx, `SELECT accept_messages FROM `+table+` WHERE `+key+` = $1 AND deleted_at IS NULL`, id).Scan(&ok)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return false, ErrGroupNotFound
	}
	return ok, err
}

// SetAccepts — «Принимать сообщения».
func (t Threads) SetAccepts(ctx context.Context, kind, id string, on bool) error {
	table, key := entityTable(kind)
	_, err := t.s.pool.Exec(ctx, `UPDATE `+table+` SET accept_messages = $2 WHERE `+key+` = $1 AND deleted_at IS NULL`, id, on)
	return err
}

const threadSelect = `
	SELECT t.thread_id::text, t.entity_kind, t.entity_id::text, t.user_id::text, t.user_state, t.user_read, t.entity_read,
	       t.user_blocked, t.last_id, t.last_at,
	       COALESCE(g.title, c.title, cm.title, ''),
	       COALESCE((SELECT m.preview FROM entity_thread_messages m WHERE m.thread_id = t.thread_id
	                  ORDER BY m.message_id DESC LIMIT 1), '')`

const threadJoin = `
	  FROM entity_threads t
	  LEFT JOIN groups g       ON t.entity_kind = 'group'     AND g.group_id = t.entity_id
	  LEFT JOIN channels c     ON t.entity_kind = 'channel'   AND c.channel_id = t.entity_id
	  LEFT JOIN communities cm ON t.entity_kind = 'community' AND cm.community_id = t.entity_id`

func scanThread(row pgx.Row, unread *int64) (Thread, error) {
	var th Thread
	dest := []any{&th.ThreadID, &th.EntityKind, &th.EntityID, &th.UserID, &th.UserState, &th.UserRead, &th.EntityRead,
		&th.UserBlocked, &th.LastID, &th.LastAt, &th.Title, &th.Preview}
	if unread != nil {
		dest = append(dest, unread)
	}
	err := row.Scan(dest...)
	if unread != nil {
		th.Unread = *unread
	}
	return th, err
}

// Get — лента пары; нет — ErrThreadNotFound.
func (t Threads) Get(ctx context.Context, kind, id, userID string) (Thread, error) {
	th, err := scanThread(t.s.pool.QueryRow(ctx, threadSelect+threadJoin+`
		 WHERE t.entity_kind = $1 AND t.entity_id = $2 AND t.user_id = $3`, kind, id, userID), nil)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return th, ErrThreadNotFound
	}
	return th, err
}

// Ensure — лента пары, заводится первым сообщением с любой стороны.
func (t Threads) Ensure(ctx context.Context, kind, id, userID string) (string, error) {
	var threadID string
	err := t.s.pool.QueryRow(ctx, `
		INSERT INTO entity_threads (entity_kind, entity_id, user_id) VALUES ($1, $2, $3)
		ON CONFLICT (entity_kind, entity_id, user_id) DO UPDATE SET entity_kind = EXCLUDED.entity_kind
		RETURNING thread_id::text`, kind, id, userID).Scan(&threadID)
	if isForeignKey(err) || isBadUUID(err) {
		return "", ErrUserUnknown
	}
	return threadID, err
}

// Add — сообщение в ленту; вершина ленты поднимается.
func (t Threads) Add(ctx context.Context, threadID, side, authorID string, body []byte, preview string) (ThreadMessage, error) {
	m := ThreadMessage{Side: side, AuthorID: authorID, Body: body, Preview: preview}
	tx, err := t.s.pool.Begin(ctx)
	if err != nil {
		return m, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck
	if err := tx.QueryRow(ctx, `
		INSERT INTO entity_thread_messages (thread_id, side, author_id, body, preview)
		VALUES ($1, $2, $3, $4, $5) RETURNING message_id, created_at`,
		threadID, side, authorID, body, preview).Scan(&m.MessageID, &m.CreatedAt); err != nil {
		return m, err
	}
	// Своё — прочитано своей стороной сразу.
	readCol := "user_read"
	if side == "entity" {
		readCol = "entity_read"
	}
	if _, err := tx.Exec(ctx, `
		UPDATE entity_threads SET last_id = $2, last_at = $3, rev = rev + 1, `+readCol+` = $2
		 WHERE thread_id = $1`, threadID, m.MessageID, m.CreatedAt); err != nil {
		return m, err
	}
	return m, tx.Commit(ctx)
}

// PeopleToday — скольким разным людям сущность писала за сутки и писала ли уже этому (§1.9, 1, 11).
func (t Threads) PeopleToday(ctx context.Context, kind, id, userID string) (count int, already bool, err error) {
	err = t.s.pool.QueryRow(ctx, `
		SELECT count(DISTINCT th.user_id), COALESCE(bool_or(th.user_id = $3), FALSE)
		  FROM entity_threads th
		  JOIN entity_thread_messages m ON m.thread_id = th.thread_id
		 WHERE th.entity_kind = $1 AND th.entity_id = $2
		   AND m.side = 'entity' AND m.created_at > now() - interval '24 hours'`, kind, id, userID).Scan(&count, &already)
	if isBadUUID(err) {
		return 0, false, nil
	}
	return count, already, err
}

// ForUser — ленты человека, новые сверху; непрочитанное — сообщения стороны сущности после отметки.
func (t Threads) ForUser(ctx context.Context, userID string) ([]Thread, error) {
	return t.list(ctx, threadSelect+`,
	       (SELECT count(*) FROM entity_thread_messages m WHERE m.thread_id = t.thread_id AND m.side = 'entity' AND m.message_id > t.user_read)`+
		threadJoin+` WHERE t.user_id = $1 ORDER BY t.last_at DESC LIMIT 200`, userID)
}

// ForEntity — ленты сущности; непрочитанное — сообщения людей после общей отметки команды.
func (t Threads) ForEntity(ctx context.Context, kind, id string) ([]Thread, error) {
	return t.list(ctx, threadSelect+`,
	       (SELECT count(*) FROM entity_thread_messages m WHERE m.thread_id = t.thread_id AND m.side = 'user' AND m.message_id > t.entity_read)`+
		threadJoin+` WHERE t.entity_kind = $1 AND t.entity_id = $2 ORDER BY t.last_at DESC LIMIT 500`, kind, id)
}

func (t Threads) list(ctx context.Context, query string, args ...any) ([]Thread, error) {
	rows, err := t.s.pool.Query(ctx, query, args...)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []Thread
	for rows.Next() {
		var unread int64
		th, err := scanThread(rows, &unread)
		if err != nil {
			return nil, err
		}
		out = append(out, th)
	}
	return out, rows.Err()
}

// Messages — сообщения ленты после [after], по порядку.
func (t Threads) Messages(ctx context.Context, threadID string, after int64, limit int) ([]ThreadMessage, error) {
	if limit <= 0 || limit > 200 {
		limit = 100
	}
	rows, err := t.s.pool.Query(ctx, `
		SELECT message_id, side, author_id::text, body, preview, created_at FROM entity_thread_messages
		 WHERE thread_id = $1 AND message_id > $2 ORDER BY message_id LIMIT $3`, threadID, after, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ThreadMessage
	for rows.Next() {
		var m ThreadMessage
		if err := rows.Scan(&m.MessageID, &m.Side, &m.AuthorID, &m.Body, &m.Preview, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// SetUserState — включено, выключено, заблокировано (у человека).
func (t Threads) SetUserState(ctx context.Context, threadID, state string) error {
	_, err := t.s.pool.Exec(ctx, `UPDATE entity_threads SET user_state = $2, rev = rev + 1 WHERE thread_id = $1`, threadID, state)
	return err
}

// SetBlocked — сущность блокирует человека (он ей не пишет).
func (t Threads) SetBlocked(ctx context.Context, threadID string, blocked bool) error {
	_, err := t.s.pool.Exec(ctx, `UPDATE entity_threads SET user_blocked = $2, rev = rev + 1 WHERE thread_id = $1`, threadID, blocked)
	return err
}

// Read — отметка прочтения стороны (только вверх).
func (t Threads) Read(ctx context.Context, threadID string, entitySide bool, upTo int64) error {
	col := "user_read"
	if entitySide {
		col = "entity_read"
	}
	_, err := t.s.pool.Exec(ctx, `UPDATE entity_threads SET `+col+` = GREATEST(`+col+`, $2) WHERE thread_id = $1`, threadID, upTo)
	return err
}
