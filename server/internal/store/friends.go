// Друзья — список аккаунта: кого он читает и кому открыта его личная группа.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5/pgconn"
)

// ErrSelfFriend — попытка добавить в друзья себя. Отдельная ошибка, а не «плохой
// запрос»: клиент показывает своё имя в поиске так же, как чужие, и нажатие по
// себе — обычная человеческая ошибка, а не поломка.
var ErrSelfFriend = errors.New("сам себе не друг")

// AddFriend — добавить человека в свой список.
//
// Оба идентификатора приходят как user_id (личности), а хранятся как person_id
// (аккаунты): друзья принадлежат аккаунту и переживают смену личности (0019).
// Перевод делает сам запрос — иначе вызывающему пришлось бы знать про эту разницу,
// и однажды кто-нибудь сохранил бы личность.
//
// Повторное добавление не ошибка и не плодит строк: человек нажимает «добавить»
// второй раз, когда не заметил, что уже добавил, — отвечать ему отказом не за что.
func (s *Store) AddFriend(ctx context.Context, ownerUserID, friendUserID string) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO friends (owner_id, friend_id)
		SELECT o.person_id, f.person_id
		FROM users o, users f
		WHERE o.user_id = $1 AND f.user_id = $2
		ON CONFLICT DO NOTHING`, ownerUserID, friendUserID)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.ConstraintName == "chk_friends_not_self" {
		return ErrSelfFriend
	}
	if isBadUUID(err) {
		return ErrUserUnknown
	}
	return err
}

// RemoveFriend — убрать из своего списка.
//
// Подписку на ленту снимает вызывающий: она живёт в другой подсистеме, и тихо
// трогать её отсюда значило бы прятать в «убрать друга» второе действие.
func (s *Store) RemoveFriend(ctx context.Context, ownerUserID, friendUserID string) error {
	_, err := s.pool.Exec(ctx, `
		DELETE FROM friends
		WHERE owner_id = (SELECT person_id FROM users WHERE user_id = $1)
		  AND friend_id = (SELECT person_id FROM users WHERE user_id = $2)`,
		ownerUserID, friendUserID)
	if isBadUUID(err) {
		return nil
	}
	return err
}

// ListFriends — свой список: текущие личности друзей.
//
// Отдаются user_id, а не person_id: клиент работает с личностями — ими подписаны
// сообщения, ими адресованы ленты. Аккаунт наружу не выходит вовсе.
func (s *Store) ListFriends(ctx context.Context, ownerUserID string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT u.user_id
		FROM friends f
		JOIN users u ON u.person_id = f.friend_id AND u.valid_to IS NULL
		WHERE f.owner_id = (SELECT person_id FROM users WHERE user_id = $1)
		ORDER BY f.created_at`, ownerUserID)
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

// IsFriend — есть ли человек в списке владельца.
//
// Нужен аудитории личной группы: она спрашивает про одного, а не читает список
// целиком (ADR-0018 после Д1б берёт круг отсюда, а не из книги).
func (s *Store) IsFriend(ctx context.Context, ownerUserID, otherUserID string) (bool, error) {
	var есть bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS(
		  SELECT 1 FROM friends
		  WHERE owner_id = (SELECT person_id FROM users WHERE user_id = $1)
		    AND friend_id = (SELECT person_id FROM users WHERE user_id = $2))`,
		ownerUserID, otherUserID).Scan(&есть)
	if isBadUUID(err) {
		return false, nil
	}
	return есть, err
}
