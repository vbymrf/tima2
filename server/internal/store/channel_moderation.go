// Выключатели обсуждения, удаление записей и модераторы канала
// (ПЛАН-КАНАЛОВ К3, ADR-0024 §6).
//
// Все проверки прав здесь стоят **внутри запроса**, а не перед ним. Причина одна и та же
// во всех четырёх методах: прочитать роль, а потом записать — значит согласиться с тем,
// что между чтением и записью роль меняется. Условие в `WHERE` этого окна не оставляет, а
// «сколько строк изменилось» и есть ответ, было ли право.
package store

import (
	"context"
	"errors"
)

// ErrNotAllowed — этому человеку это действие не разрешено.
var ErrNotAllowed = errors.New("действие не разрешено")

// ErrCommentsClosed — обсуждение выключено: у канала целиком или у этой записи.
var ErrCommentsClosed = errors.New("обсуждение закрыто")

// SetChannelComments — «канал без обсуждений». Только владелец.
func (s *Store) SetChannelComments(ctx context.Context, channelID, ownerID string, enabled bool) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE channels SET comments_enabled = $3
		 WHERE channel_id = $1 AND owner_id = $2 AND deleted_at IS NULL`,
		channelID, ownerID, enabled)
	if err != nil {
		if isBadUUID(err) {
			return ErrChannelNotFound
		}
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrNotAllowed
	}
	return nil
}

// SetPostComments — «эту запись не обсуждаем». Владелец канала и модератор.
//
// Автору записи это не даётся: в канале пишет владелец, а комментарий закрывать нечем —
// у комментария своего разговора нет вовсе (глубина два уровня).
func (s *Store) SetPostComments(ctx context.Context, channelID string, postID uint64, actorID string, closed bool) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE channel_posts p SET comments_closed = $4
		 WHERE p.channel_id = $1 AND p.post_id = $2 AND NOT p.deleted
		   AND p.parent_post_id IS NULL
		   AND (EXISTS (SELECT 1 FROM channels c
		                 WHERE c.channel_id = p.channel_id AND c.owner_id = $3)
		     OR EXISTS (SELECT 1 FROM channel_moderators m
		                 WHERE m.channel_id = p.channel_id AND m.user_id = $3))`,
		channelID, postID, actorID, closed)
	if err != nil {
		if isBadUUID(err) {
			return ErrChannelNotFound
		}
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrNotAllowed
	}
	return nil
}

// CommentsAllowed — принимает ли эта запись новые комментарии.
//
// Одним запросом на оба выключателя: канал целиком и эта запись. Раздельными были бы два
// обращения ради одного ответа, а ответ нужен на каждый комментарий.
func (s *Store) CommentsAllowed(ctx context.Context, channelID string, rootID uint64) (bool, error) {
	var allowed bool
	err := s.pool.QueryRow(ctx, `
		SELECT c.comments_enabled AND NOT p.comments_closed
		  FROM channel_posts p
		  JOIN channels c ON c.channel_id = p.channel_id
		 WHERE p.channel_id = $1 AND p.post_id = $2 AND NOT p.deleted`,
		channelID, rootID).Scan(&allowed)
	if err != nil {
		if isBadUUID(err) {
			return false, ErrChannelNotFound
		}
		return false, err
	}
	return allowed, nil
}

// DeleteChannelPost — убрать запись или комментарий.
//
// **Автор убирает своё, владелец и модератор канала — любое у себя** (решение заказчика
// 2026-09-08). Помечает удалённым, а не стирает: у записи есть история, а у ленты —
// ссылки на неё.
//
// Удалённый корень уносит с собой и разговор: комментарий не отдаётся без корня
// (ADR-0024 §9). Отдельно вычищать комментарии не нужно и не следует — иначе удаление
// записи с сотней ответов становится сотней UPDATE-ов.
func (s *Store) DeleteChannelPost(ctx context.Context, channelID string, postID uint64, actorID string) error {
	ct, err := s.pool.Exec(ctx, `
		UPDATE channel_posts p SET deleted = TRUE
		 WHERE p.channel_id = $1 AND p.post_id = $2 AND NOT p.deleted
		   AND (p.author_id = $3
		     OR EXISTS (SELECT 1 FROM channels c
		                 WHERE c.channel_id = p.channel_id AND c.owner_id = $3)
		     OR EXISTS (SELECT 1 FROM channel_moderators m
		                 WHERE m.channel_id = p.channel_id AND m.user_id = $3))`,
		channelID, postID, actorID)
	if err != nil {
		if isBadUUID(err) {
			return ErrChannelNotFound
		}
		return err
	}
	if ct.RowsAffected() == 0 {
		return ErrNotAllowed
	}
	return nil
}

// SetChannelModerator — назначить или снять модератора. Только владелец.
//
// Один метод на оба действия: назначение и снятие различаются одним булевым значением, а
// два метода означали бы два места, где проверяется «а владелец ли ты».
func (s *Store) SetChannelModerator(ctx context.Context, channelID, ownerID, userID string, on bool) error {
	var q string
	if on {
		q = `INSERT INTO channel_moderators (channel_id, user_id)
		     SELECT $1, $3 FROM channels c
		      WHERE c.channel_id = $1 AND c.owner_id = $2 AND c.deleted_at IS NULL
		     ON CONFLICT DO NOTHING`
	} else {
		q = `DELETE FROM channel_moderators m
		      WHERE m.channel_id = $1 AND m.user_id = $3
		        AND EXISTS (SELECT 1 FROM channels c
		                     WHERE c.channel_id = $1 AND c.owner_id = $2)`
	}
	ct, err := s.pool.Exec(ctx, q, channelID, ownerID, userID)
	if err != nil {
		if isBadUUID(err) {
			return ErrChannelNotFound
		}
		return err
	}
	// Повтор безвреден и не ошибка: назначили уже назначенного — состояние то же самое.
	// Отличить «не владелец» от «уже так» нельзя по числу строк, поэтому владелец
	// проверяется отдельным запросом только тогда, когда строк не изменилось.
	if ct.RowsAffected() == 0 {
		var owner bool
		if err := s.pool.QueryRow(ctx,
			`SELECT EXISTS (SELECT 1 FROM channels WHERE channel_id = $1 AND owner_id = $2)`,
			channelID, ownerID).Scan(&owner); err != nil {
			return err
		}
		if !owner {
			return ErrNotAllowed
		}
	}
	return nil
}

// ChannelModerators — кто модерирует этот канал.
func (s *Store) ChannelModerators(ctx context.Context, channelID string) ([]string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT user_id FROM channel_moderators WHERE channel_id = $1 ORDER BY added_at`, channelID)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
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
