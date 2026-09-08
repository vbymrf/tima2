// Поимённое разрешение у записи канала и ленты (ПЛАН-КАНАЛОВ К4, ADR-0019 §8).
//
// Разрешение даётся на ОДНУ запись, а не на контейнер: «покажи это ему», а не «пусть
// видит всё моё третьего уровня». В группе устроено иначе, и это не расхождение, а
// разные просьбы.
package store

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgconn"
)

// commandTag — результат Exec. Псевдоним ради одного места: выдача и снятие разрешения
// различаются запросом и числом параметров, и без него пришлось бы протаскивать
// неиспользуемый параметр в DELETE только ради общей строки.
type commandTag = pgconn.CommandTag

// FeedGrant — кому открыта запись и до каких пор.
type FeedGrant struct {
	UserID    string
	Until     *time.Time // nil = бессрочно
	GrantedAt time.Time
}

// SetFeedGrant — открыть запись человеку или закрыть обратно. Только владелец канала.
//
// Право проверяется внутри запроса — тем же приёмом, что в модерации: чтение роли с
// последующей записью оставляет окно, в котором роль меняется.
func (s *Store) SetFeedGrant(ctx context.Context, channelID string, postID uint64, ownerID, userID string, until *time.Time, grant bool) error {
	ct, err := func() (commandTag, error) {
		if grant {
			return s.pool.Exec(ctx, `
				INSERT INTO feed_level_grants (channel_id, post_id, user_id, until)
				SELECT $1, $2, $4, $5 FROM channel_posts p
				 JOIN channels c ON c.channel_id = p.channel_id
				 WHERE p.channel_id = $1 AND p.post_id = $2 AND NOT p.deleted
				   AND p.parent_post_id IS NULL AND c.owner_id = $3
				ON CONFLICT (channel_id, post_id, user_id)
				DO UPDATE SET until = EXCLUDED.until, granted_at = now()`,
				channelID, postID, ownerID, userID, until)
		}
		return s.pool.Exec(ctx, `
			DELETE FROM feed_level_grants g
			 WHERE g.channel_id = $1 AND g.post_id = $2 AND g.user_id = $4
			   AND EXISTS (SELECT 1 FROM channels c
			                WHERE c.channel_id = $1 AND c.owner_id = $3)`,
			channelID, postID, ownerID, userID)
	}()
	if err != nil {
		if isBadUUID(err) {
			return ErrChannelNotFound
		}
		return err
	}
	if ct.RowsAffected() == 0 {
		// Ничего не изменилось: либо не владелец, либо снимаем то, чего не было. Второе
		// не ошибка — состояние ровно такое, какого просили.
		var owner bool
		if err := s.pool.QueryRow(ctx,
			`SELECT EXISTS (SELECT 1 FROM channels WHERE channel_id = $1 AND owner_id = $2)`,
			channelID, ownerID).Scan(&owner); err != nil {
			return err
		}
		if !owner {
			return ErrNotAllowed
		}
		if grant {
			// Владелец, но вставки не случилось — записи нет или она комментарий.
			return ErrChannelNotFound
		}
	}
	return nil
}

// FeedGrantActive — открыта ли эта запись этому человеку прямо сейчас.
//
// Срок проверяется здесь же: истёкшее разрешение не удаляется фоновой задачей, потому что
// удалять его незачем — оно перестаёт действовать само, а строка остаётся историей того,
// что доступ давали.
func (s *Store) FeedGrantActive(ctx context.Context, channelID string, postID uint64, userID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS (
		    SELECT 1 FROM feed_level_grants
		     WHERE channel_id = $1 AND post_id = $2 AND user_id = $3
		       AND (until IS NULL OR until > now()))`,
		channelID, postID, userID).Scan(&ok)
	if err != nil && isBadUUID(err) {
		return false, nil
	}
	return ok, err
}

// FeedGrants — кому открыта эта запись. Список видит владелец канала.
//
// Истёкшие строки отдаются тоже: владельцу полезно видеть, что срок кончился, — иначе
// «я же ему открывал» превращается в спор с приложением.
func (s *Store) FeedGrants(ctx context.Context, channelID string, postID uint64) ([]FeedGrant, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT user_id, until, granted_at
		  FROM feed_level_grants
		 WHERE channel_id = $1 AND post_id = $2
		 ORDER BY granted_at`, channelID, postID)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []FeedGrant
	for rows.Next() {
		var g FeedGrant
		if err := rows.Scan(&g.UserID, &g.Until, &g.GrantedAt); err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}
