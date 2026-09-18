package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// Копия личных данных аккаунта — книга и разделы — блобом, который сервер не читает
// (миграция 0051, ПЛАН-РАЗДЕЛОВ Р2а).

// ErrStoreRevision — ревизия не следующая: кто-то сохранил раньше. Клиент забирает
// свежее, сливает и повторяет.
var ErrStoreRevision = errors.New("ревизия копии не следующая")

// ErrStoreEmpty — копии этого вида ещё нет.
var ErrStoreEmpty = errors.New("копии нет")

// AccountBlob — что лежит у сервера по (аккаунт, вид).
type AccountBlob struct {
	Kind     string
	Revision int64
	DeviceID string
	Blob     []byte
}

// AccountStore — забрать копию. ErrStoreEmpty, если её ещё не сохраняли.
func (s *Store) AccountStore(ctx context.Context, userID, kind string) (AccountBlob, error) {
	out := AccountBlob{Kind: kind}
	err := s.pool.QueryRow(ctx, `
		SELECT revision, device_id::text, blob FROM account_store
		WHERE user_id = $1 AND kind = $2`, userID, kind).Scan(&out.Revision, &out.DeviceID, &out.Blob)
	switch {
	case errors.Is(err, pgx.ErrNoRows), err != nil && isBadUUID(err):
		return out, ErrStoreEmpty
	case err != nil:
		return out, err
	}
	return out, nil
}

// PutAccountStore — сохранить копию ревизией ровно текущая + 1 (первая — 1).
//
// Условие проверяется в одном запросе с записью: две проверки-потом-записи с двух
// устройств прошли бы обе.
func (s *Store) PutAccountStore(ctx context.Context, userID string, in AccountBlob) error {
	// Два пути, и выбирает их ревизия: единица — только ВСТАВКА в пустое место, всё прочее
	// — только ОБНОВЛЕНИЕ строки с предыдущей ревизией. Один INSERT … ON CONFLICT этого не
	// умел: вставка в пустое место проходила с любой ревизией, и тест это поймал — второе
	// устройство никогда не совпало бы по счёту с первым, начавшим с семёрки.
	var tag interface{ RowsAffected() int64 }
	var err error
	if in.Revision == 1 {
		tag, err = s.pool.Exec(ctx, `
			INSERT INTO account_store (user_id, kind, revision, device_id, blob, updated_at)
			VALUES ($1, $2, 1, $3, $4, now())
			ON CONFLICT (user_id, kind) DO NOTHING`,
			userID, in.Kind, in.DeviceID, in.Blob)
	} else {
		tag, err = s.pool.Exec(ctx, `
			UPDATE account_store
			   SET revision = $3, device_id = $4, blob = $5, updated_at = now()
			 WHERE user_id = $1 AND kind = $2 AND revision = $3 - 1`,
			userID, in.Kind, in.Revision, in.DeviceID, in.Blob)
	}
	if err != nil {
		if isBadUUID(err) {
			return ErrUserUnknown
		}
		return err
	}
	// Ни вставки, ни обновления: строка уже есть (для единицы) либо ревизия не следующая.
	if tag.RowsAffected() == 0 {
		return ErrStoreRevision
	}
	return nil
}

// StoreGroup — служебная private-группа аккаунта; заводится при первом обращении.
//
// Участник один — сам человек; ключ группы заворачивается на все его устройства теми же
// ручками, что у любой группы. Имени у группы нет, в каталоге её нет.
func (s *Store) StoreGroup(ctx context.Context, userID string) (string, error) {
	var existing *string
	if err := s.pool.QueryRow(ctx,
		`SELECT store_group_id::text FROM users WHERE user_id = $1`, userID).Scan(&existing); err != nil {
		if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
			return "", ErrUserUnknown
		}
		return "", err
	}
	if existing != nil {
		return *existing, nil
	}
	id, err := s.CreateGroup(ctx, Group{Kind: "private", Title: "", OwnerID: userID})
	if err != nil {
		return "", err
	}
	// Два устройства могли завести группу одновременно: побеждает та, что записалась
	// первой, вторая остаётся пустой сиротой — это дешевле, чем блокировка на каждом чтении.
	tag, err := s.pool.Exec(ctx,
		`UPDATE users SET store_group_id = $2 WHERE user_id = $1 AND store_group_id IS NULL`, userID, id)
	if err != nil {
		return "", err
	}
	if tag.RowsAffected() == 0 {
		return s.StoreGroup(ctx, userID)
	}
	return id, nil
}
