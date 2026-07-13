// Публичные каналы (data-model.md §3): вещание владельца/админов подписчикам.
// Контент публичный (не E2E) — осознанно для трансляции.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

var ErrChannelNotFound = errors.New("канал не найден")

type Channel struct {
	ChannelID   string
	Title       string
	Description string
	OwnerID     string
	IsPublic    bool
}

type ChannelView struct {
	Channel
	Subscribed bool
	Owner      bool
}

type ChannelPost struct {
	ChannelID       string
	PostID          uint64
	AuthorID        string
	Text            string
	CreatedAtUnixMs int64
}

// CreateChannel создаёт канал и подписывает владельца одной транзакцией.
func (s *Store) CreateChannel(ctx context.Context, c Channel) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck

	var id string
	if err := tx.QueryRow(ctx, `
		INSERT INTO channels (title, description, owner_id, is_public)
		VALUES ($1,$2,$3,$4) RETURNING channel_id`,
		c.Title, c.Description, c.OwnerID, c.IsPublic).Scan(&id); err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO channel_subscriptions (channel_id, subscriber_id) VALUES ($1,$2)`, id, c.OwnerID); err != nil {
		return "", err
	}
	return id, tx.Commit(ctx)
}

func (s *Store) GetChannel(ctx context.Context, channelID string) (Channel, error) {
	var c Channel
	err := s.pool.QueryRow(ctx, `
		SELECT channel_id, title, description, owner_id, is_public
		FROM channels WHERE channel_id = $1 AND deleted_at IS NULL`, channelID).
		Scan(&c.ChannelID, &c.Title, &c.Description, &c.OwnerID, &c.IsPublic)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return c, ErrChannelNotFound
	}
	return c, err
}

// MyChannels — каналы, где пользователь владелец или подписчик.
func (s *Store) MyChannels(ctx context.Context, userID string) ([]ChannelView, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT c.channel_id, c.title, c.description, c.owner_id, c.is_public,
		       TRUE AS subscribed, (c.owner_id = $1) AS owner
		FROM channels c
		JOIN channel_subscriptions s ON s.channel_id = c.channel_id AND s.subscriber_id = $1
		WHERE c.deleted_at IS NULL
		ORDER BY c.created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	return scanChannelViews(rows)
}

// DiscoverChannels — публичные каналы, на которые пользователь ещё НЕ подписан.
func (s *Store) DiscoverChannels(ctx context.Context, userID string, limit int) ([]ChannelView, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	rows, err := s.pool.Query(ctx, `
		SELECT c.channel_id, c.title, c.description, c.owner_id, c.is_public,
		       FALSE AS subscribed, (c.owner_id = $1) AS owner
		FROM channels c
		WHERE c.deleted_at IS NULL AND c.is_public
		  AND NOT EXISTS (SELECT 1 FROM channel_subscriptions s
		                  WHERE s.channel_id = c.channel_id AND s.subscriber_id = $1)
		ORDER BY c.created_at DESC
		LIMIT $2`, userID, limit)
	if err != nil {
		return nil, err
	}
	return scanChannelViews(rows)
}

func scanChannelViews(rows pgx.Rows) ([]ChannelView, error) {
	defer rows.Close()
	var out []ChannelView
	for rows.Next() {
		var v ChannelView
		if err := rows.Scan(&v.ChannelID, &v.Title, &v.Description, &v.OwnerID, &v.IsPublic,
			&v.Subscribed, &v.Owner); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func (s *Store) Subscribe(ctx context.Context, channelID, userID string) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO channel_subscriptions (channel_id, subscriber_id) VALUES ($1,$2)
		ON CONFLICT DO NOTHING`, channelID, userID)
	return err
}

func (s *Store) Unsubscribe(ctx context.Context, channelID, userID string) error {
	_, err := s.pool.Exec(ctx, `
		DELETE FROM channel_subscriptions WHERE channel_id = $1 AND subscriber_id = $2`, channelID, userID)
	return err
}

func (s *Store) IsSubscribed(ctx context.Context, channelID, userID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS(SELECT 1 FROM channel_subscriptions WHERE channel_id=$1 AND subscriber_id=$2)`,
		channelID, userID).Scan(&ok)
	return ok, err
}

// SubscriberIDs — user_id подписчиков (для fan-out поста по устройствам через notify).
func (s *Store) SubscriberIDs(ctx context.Context, channelID string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT subscriber_id FROM channel_subscriptions WHERE channel_id = $1`, channelID)
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

func (s *Store) CreatePost(ctx context.Context, p ChannelPost) (uint64, error) {
	var id uint64
	err := s.pool.QueryRow(ctx, `
		INSERT INTO channel_posts (channel_id, author_id, text, created_at_unix_ms)
		VALUES ($1,$2,$3,$4) RETURNING post_id`,
		p.ChannelID, p.AuthorID, p.Text, p.CreatedAtUnixMs).Scan(&id)
	return id, err
}

// ListPosts — лента канала (новые → старые).
func (s *Store) ListPosts(ctx context.Context, channelID string, before uint64, limit int) ([]ChannelPost, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	if before == 0 {
		before = ^uint64(0) >> 1
	}
	rows, err := s.pool.Query(ctx, `
		SELECT channel_id, post_id, author_id, text, created_at_unix_ms
		FROM channel_posts
		WHERE channel_id = $1 AND post_id < $2 AND NOT deleted
		ORDER BY post_id DESC LIMIT $3`, channelID, before, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ChannelPost
	for rows.Next() {
		var p ChannelPost
		if err := rows.Scan(&p.ChannelID, &p.PostID, &p.AuthorID, &p.Text, &p.CreatedAtUnixMs); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}
