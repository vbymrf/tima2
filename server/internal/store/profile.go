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
