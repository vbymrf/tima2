// Профиль «кто я» — то, что человек видит о своём аккаунте (ПЛАН-КОНТАКТОВ.md, Д8).
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// Me — запись о себе. Собирается из аккаунта (persons) и текущей личности (users).
//
// Телефон и имя открываются из шифра здесь же: за пределы хранилища зашифрованное не
// выходит. Пустая строка — не задано.
type Me struct {
	Phone    string
	Name     string
	Nickname string
	// Ник задан ЭТОЙ личностью — менять нельзя (0050). Новая личность получит право снова.
	NicknameLocked bool
	// Медиа-объект аватара. Пусто — картинки нет, рисуются буквы.
	AvatarMediaID string
}

// Me — профиль по user_id текущей личности.
func (s *Store) Me(ctx context.Context, userID string) (Me, error) {
	var (
		phoneEnc, nameEnc []byte
		nick, setBy, avatar *string
	)
	err := s.pool.QueryRow(ctx, `
		SELECT p.phone_enc, p.name_enc, p.nickname, p.nickname_set_by::text, p.avatar_media_id::text
		  FROM users u JOIN persons p ON p.person_id = u.person_id
		 WHERE u.user_id = $1`, userID).Scan(&phoneEnc, &nameEnc, &nick, &setBy, &avatar)
	if errors.Is(err, pgx.ErrNoRows) {
		return Me{}, ErrUserUnknown
	}
	if err != nil {
		return Me{}, err
	}
	me := Me{}
	if len(phoneEnc) > 0 {
		if me.Phone, err = s.pii.Open(phoneEnc); err != nil {
			return Me{}, err
		}
	}
	if len(nameEnc) > 0 {
		if me.Name, err = s.pii.Open(nameEnc); err != nil {
			return Me{}, err
		}
	}
	if nick != nil {
		me.Nickname = *nick
	}
	me.NicknameLocked = nick != nil && setBy != nil && *setBy == userID
	if avatar != nil {
		me.AvatarMediaID = *avatar
	}
	return me, nil
}

// ErrAvatarNotOwned — медиа не этого человека или ещё не загружено до конца.
var ErrAvatarNotOwned = errors.New("медиа не принадлежит аккаунту или не завершено")

// SetAvatar — сменить или убрать аватар. Пустой mediaID убирает.
//
// Проверка владения и статуса — в том же запросе, что и запись: между отдельным
// SELECT и UPDATE объект могли бы удалить. Ноль строк при живом аккаунте — чужое или
// незавершённое медиа.
func (s *Store) SetAvatar(ctx context.Context, userID, mediaID string) error {
	if mediaID == "" {
		_, err := s.pool.Exec(ctx, `
			UPDATE persons SET avatar_media_id = NULL
			WHERE person_id = (SELECT person_id FROM users WHERE user_id = $1)`, userID)
		return err
	}
	tag, err := s.pool.Exec(ctx, `
		UPDATE persons SET avatar_media_id = $2::uuid
		WHERE person_id = (SELECT person_id FROM users WHERE user_id = $1)
		  AND EXISTS (SELECT 1 FROM media_objects
		               WHERE media_id = $2::uuid AND owner_id = $1 AND status = 'complete')`,
		userID, mediaID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrAvatarNotOwned
	}
	return nil
}

// AvatarsOf — аватары по user_id: media_id тех, у кого он поставлен. Пустой в карту не
// попадает — как и у Nicknames: клиент обязан отличать «аватара нет» от «сервер не
// ответил». Аватар публичен так же, как ник: человек поставил его сам, и видят его все,
// кто видит его сообщения. Медиа по id отдаётся любому вошедшему — так устроен mediaURL.
func (s *Store) AvatarsOf(ctx context.Context, ids []string) (map[string]string, error) {
	out := make(map[string]string, len(ids))
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `
		SELECT u.user_id, p.avatar_media_id::text FROM users u
		JOIN persons p ON p.person_id = u.person_id
		WHERE u.user_id = ANY($1) AND p.avatar_media_id IS NOT NULL`, ids)
	if err != nil {
		if isBadUUID(err) {
			return out, nil
		}
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, media string
		if err := rows.Scan(&id, &media); err != nil {
			return nil, err
		}
		out[id] = media
	}
	return out, rows.Err()
}
