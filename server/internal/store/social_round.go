// Социум, девятый–одиннадцатый круг (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md СЦ18–СЦ23; заказчик 2026-11-23): роль «админ» у
// канала, «Показывать участников», бан подписчика канала и сообщества с наследованием на элементы сообщества,
// исключение из сообщества вместе с элементами, тело записи канала документом, правка сообщений описания.
//
// Отдельными функциями, а не методами `*Store`: у хранилища бюджет методов (architecture_test).
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ErrBanned — человек забанен в этой сущности или в сообществе, куда она входит.
var ErrBanned = errors.New("забанен")

// ChannelRole — роль человека в канале: owner, admin, moderator, member (подписчик) или "" (никто).
func ChannelRole(ctx context.Context, s *Store, channelID, userID string) (string, error) {
	var role string
	err := s.pool.QueryRow(ctx, `
		SELECT CASE
		         WHEN c.owner_id = $2 THEN 'owner'
		         WHEN m.role IS NOT NULL THEN m.role
		         WHEN EXISTS (SELECT 1 FROM channel_subscriptions cs
		                       WHERE cs.channel_id = c.channel_id AND cs.subscriber_id = $2) THEN 'member'
		         ELSE '' END
		  FROM channels c
		  LEFT JOIN channel_moderators m ON m.channel_id = c.channel_id AND m.user_id = $2
		 WHERE c.channel_id = $1 AND c.deleted_at IS NULL`, channelID, userID).Scan(&role)
	if isBadUUID(err) || errors.Is(err, pgx.ErrNoRows) {
		return "", ErrChannelNotFound
	}
	return role, err
}

// ChannelStaffRoles — команда канала без владельца: номер → admin | moderator.
func ChannelStaffRoles(ctx context.Context, s *Store, channelID string) (map[string]string, error) {
	out := map[string]string{}
	rows, err := s.pool.Query(ctx, `SELECT user_id::text, role FROM channel_moderators WHERE channel_id = $1`, channelID)
	if err != nil {
		if isBadUUID(err) {
			return out, nil
		}
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, role string
		if err := rows.Scan(&id, &role); err != nil {
			return nil, err
		}
		out[id] = role
	}
	return out, rows.Err()
}

// SetChannelStaffRole — назначить (admin | moderator) или снять ("") роль в канале. Права проверяет вызывающий.
// Назначенный подписывается: команда канала — его подписчики.
func SetChannelStaffRole(ctx context.Context, s *Store, channelID, userID, role string) error {
	if role == "" {
		_, err := s.pool.Exec(ctx, `DELETE FROM channel_moderators WHERE channel_id = $1 AND user_id = $2`, channelID, userID)
		return err
	}
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `
		INSERT INTO channel_moderators (channel_id, user_id, role) VALUES ($1, $2, $3)
		ON CONFLICT (channel_id, user_id) DO UPDATE SET role = EXCLUDED.role`, channelID, userID, role); err != nil {
		if isBadUUID(err) {
			return ErrChannelNotFound
		}
		return err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO channel_subscriptions (channel_id, subscriber_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
		channelID, userID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// ShowMembersOf — «Показывать участников» сущностей вида: номер → включено. Нет в ответе — сущности нет.
func ShowMembersOf(ctx context.Context, s *Store, kind string, ids []string) (map[string]bool, error) {
	out := map[string]bool{}
	if len(ids) == 0 {
		return out, nil
	}
	table, key := entityTable(kind)
	rows, err := s.pool.Query(ctx, `SELECT `+key+`::text, show_members FROM `+table+`
		WHERE `+key+`::text = ANY($1) AND deleted_at IS NULL`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		var on bool
		if err := rows.Scan(&id, &on); err != nil {
			return nil, err
		}
		out[id] = on
	}
	return out, rows.Err()
}

// SetShowMembers — включить или выключить «Показывать участников». Права проверяет вызывающий.
func SetShowMembers(ctx context.Context, s *Store, kind, id string, on bool) error {
	table, key := entityTable(kind)
	tag, err := s.pool.Exec(ctx, `UPDATE `+table+` SET show_members = $2 WHERE `+key+`::text = $1 AND deleted_at IS NULL`, id, on)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrEntityNotFound
	}
	return nil
}

// BanUser — бан в канале или сообществе: строка бана и снятие подписки (у сообщества — и с элементов, как
// исключение). Права проверяет вызывающий.
func BanUser(ctx context.Context, s *Store, kind, id, userID, by string) error {
	if _, err := s.pool.Exec(ctx, `
		INSERT INTO entity_bans (kind, entity_id, user_id, banned_by) VALUES ($1, $2, $3, $4)
		ON CONFLICT (kind, entity_id, user_id) DO NOTHING`, kind, id, userID, by); err != nil {
		if isBadUUID(err) {
			return ErrEntityNotFound
		}
		return err
	}
	if kind == "community" {
		return ExcludeFromCommunity(ctx, s, id, userID)
	}
	_, err := s.pool.Exec(ctx, `DELETE FROM channel_subscriptions WHERE channel_id = $1 AND subscriber_id = $2`, id, userID)
	if err == nil {
		_, err = s.pool.Exec(ctx, `DELETE FROM channel_moderators WHERE channel_id = $1 AND user_id = $2`, id, userID)
	}
	return err
}

// UnbanUser — снять бан; подписка не возвращается — человек подпишется сам.
func UnbanUser(ctx context.Context, s *Store, kind, id, userID string) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM entity_bans WHERE kind = $1 AND entity_id::text = $2 AND user_id::text = $3`, kind, id, userID)
	return err
}

// BannedIDs — кто забанен в этой сущности (только её строки; наследованные от сообщества — у сообщества).
func BannedIDs(ctx context.Context, s *Store, kind, id string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT user_id::text FROM entity_bans WHERE kind = $1 AND entity_id::text = $2 ORDER BY banned_at`, kind, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var u string
		if err := rows.Scan(&u); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// BannedFrom — закрыт ли вход человеку: бан в самой сущности или в сообществе, куда она входит (СЦ23:
// «бан и исключить наследуется»). kind — group | channel | community.
func BannedFrom(ctx context.Context, s *Store, kind, id, userID string) (bool, error) {
	var banned bool
	var err error
	switch kind {
	case "community":
		err = s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM entity_bans
			WHERE kind = 'community' AND entity_id::text = $1 AND user_id::text = $2)`, id, userID).Scan(&banned)
	case "channel":
		err = s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM entity_bans b, channels c
			WHERE c.channel_id::text = $1 AND b.user_id::text = $2
			  AND ((b.kind = 'channel' AND b.entity_id = c.channel_id)
			    OR (b.kind = 'community' AND b.entity_id = c.community_id)))`, id, userID).Scan(&banned)
	default:
		err = s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM entity_bans b, groups g
			WHERE g.group_id::text = $1 AND b.user_id::text = $2
			  AND b.kind = 'community' AND b.entity_id = g.community_id)`, id, userID).Scan(&banned)
	}
	if isBadUUID(err) {
		return false, nil
	}
	return banned, err
}

// CommunitySubscriberIDs — подписчики сообщества, по времени подписки.
func CommunitySubscriberIDs(ctx context.Context, s *Store, communityID string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT subscriber_id::text FROM community_subscriptions WHERE community_id::text = $1 ORDER BY created_at`, communityID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var u string
		if err := rows.Scan(&u); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// ExcludeFromCommunity — исключение с наследованием (СЦ23): подписка на сообщество, подписки и роли в его
// каналах, участие в его группах (кроме тех, где человек владелец — владение так не снимается). Права и то, что
// цель не владелец и не админ сообщества, проверяет вызывающий.
func ExcludeFromCommunity(ctx context.Context, s *Store, communityID, userID string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	for _, q := range []string{
		`DELETE FROM community_subscriptions WHERE community_id::text = $1 AND subscriber_id::text = $2`,
		`DELETE FROM channel_subscriptions cs USING channels c
		  WHERE cs.channel_id = c.channel_id AND c.community_id::text = $1 AND cs.subscriber_id::text = $2
		    AND c.owner_id::text <> $2`,
		`DELETE FROM channel_moderators m USING channels c
		  WHERE m.channel_id = c.channel_id AND c.community_id::text = $1 AND m.user_id::text = $2`,
		`UPDATE memberships ms SET left_at = now() FROM groups g
		  WHERE ms.target_type = 'group' AND ms.target_id = g.group_id AND g.community_id::text = $1
		    AND ms.user_id::text = $2 AND ms.left_at IS NULL AND ms.role <> 'owner'`,
	} {
		if _, err := tx.Exec(ctx, q, communityID, userID); err != nil {
			if isBadUUID(err) {
				return ErrCommunityNotFound
			}
			return err
		}
	}
	return tx.Commit(ctx)
}

// SetPostBody — тело записи или комментария канала документом (СЦ22). Пустое — не пишется.
func SetPostBody(ctx context.Context, s *Store, channelID string, postID uint64, body []byte) error {
	if len(body) == 0 {
		return nil
	}
	_, err := s.pool.Exec(ctx, `UPDATE channel_posts SET body = $3 WHERE channel_id = $1 AND post_id = $2`, channelID, postID, body)
	return err
}

// PostBodiesOf — тела записей канала по номерам; записей без тела в ответе нет.
func PostBodiesOf(ctx context.Context, s *Store, channelID string, ids []uint64) (map[uint64][]byte, error) {
	out := map[uint64][]byte{}
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `
		SELECT post_id, body FROM channel_posts WHERE channel_id = $1 AND post_id = ANY($2) AND body IS NOT NULL`,
		channelID, ids)
	if err != nil {
		if isBadUUID(err) {
			return out, nil
		}
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id uint64
		var body []byte
		if err := rows.Scan(&id, &body); err != nil {
			return nil, err
		}
		out[id] = body
	}
	return out, rows.Err()
}

// EditChannelPost — правка записи канала автором (СЦ18): текст, узлы, разметка, тело. ErrNotAllowed — не автор
// или записи нет.
func EditChannelPost(ctx context.Context, s *Store, channelID string, postID uint64, authorID, text string, nodes []string, markup, body []byte) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE channel_posts SET text = $4, nodes = $5, markup = $6, body = COALESCE($7, body), edited_at_unix_ms = $8
		 WHERE channel_id = $1 AND post_id = $2 AND author_id::text = $3 AND NOT deleted`,
		channelID, postID, authorID, text, nodes, markup, body, time.Now().UnixMilli())
	if err != nil {
		if isBadUUID(err) {
			return ErrChannelNotFound
		}
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotAllowed
	}
	return nil
}

// EditCommunityMessage — правка сообщения описания сообщества автором (СЦ18).
func EditCommunityMessage(ctx context.Context, s *Store, communityID string, messageID int64, authorID string, nodes []string, markup []byte) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE community_messages SET nodes = $4, markup = $5, edited_at_unix_ms = $6
		 WHERE community_id::text = $1 AND message_id = $2 AND author_id::text = $3 AND NOT deleted`,
		communityID, messageID, authorID, nodes, markup, time.Now().UnixMilli())
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotAllowed
	}
	return nil
}

// DeleteCommunityMessage — удалить сообщение описания сообщества (права проверяет вызывающий: владелец и админы).
// Было суперсообщением — место освобождается.
func DeleteCommunityMessage(ctx context.Context, s *Store, communityID string, messageID int64) error {
	tag, err := s.pool.Exec(ctx, `
		UPDATE community_messages SET deleted = TRUE WHERE community_id::text = $1 AND message_id = $2 AND NOT deleted`,
		communityID, messageID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrMessageGone
	}
	_, err = s.pool.Exec(ctx, `UPDATE communities SET showcase_super = NULL
		WHERE community_id::text = $1 AND showcase_super = $2`, communityID, messageID)
	return err
}

// CommunityMessageAuthors — номер сообщения описания сообщества → автор и время правки: для списка
// «Управление → Сообщения» (кнопка «Править» — у своих).
func CommunityMessageAuthors(ctx context.Context, s *Store, communityID string) (map[int64]string, map[int64]int64, error) {
	authors, edited := map[int64]string{}, map[int64]int64{}
	rows, err := s.pool.Query(ctx, `
		SELECT message_id, author_id::text, edited_at_unix_ms FROM community_messages
		 WHERE community_id::text = $1 AND NOT deleted`, communityID)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, at int64
		var a string
		if err := rows.Scan(&id, &a, &at); err != nil {
			return nil, nil, err
		}
		authors[id], edited[id] = a, at
	}
	return authors, edited, rows.Err()
}

// ChannelSlowMode — медленный режим комментариев канала, секунды (0 — выключен).
func ChannelSlowMode(ctx context.Context, s *Store, channelID string) (int, error) {
	var sec int
	err := s.pool.QueryRow(ctx, `SELECT slow_mode_sec FROM channels WHERE channel_id::text = $1`, channelID).Scan(&sec)
	if isBadUUID(err) || errors.Is(err, pgx.ErrNoRows) {
		return 0, nil
	}
	return sec, err
}

// SetChannelSlowMode — медленный режим комментариев канала. Права проверяет вызывающий.
func SetChannelSlowMode(ctx context.Context, s *Store, channelID string, sec int) error {
	tag, err := s.pool.Exec(ctx, `UPDATE channels SET slow_mode_sec = $2 WHERE channel_id::text = $1 AND deleted_at IS NULL`, channelID, sec)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrChannelNotFound
	}
	return nil
}

// CommentedWithin — писал ли человек комментарий в канале за последние sec секунд (медленный режим).
func CommentedWithin(ctx context.Context, s *Store, channelID, userID string, sec int) (bool, error) {
	var recent bool
	err := s.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM channel_posts
		WHERE channel_id::text = $1 AND author_id::text = $2 AND parent_post_id IS NOT NULL
		  AND created_at > now() - make_interval(secs => $3))`, channelID, userID, sec).Scan(&recent)
	return recent, err
}
