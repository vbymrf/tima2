// Личности аккаунта и цепочка между ними.
//
// «Начать заново» не удаляет прежнюю личность, а заводит новую и связывает с
// прежней: по этой цепочке потом возвращаются по старой фразе.
package store

import (
	"bytes"
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// StartNewIdentity закрывает текущую личность аккаунта и заводит новую, связав её с
// прежней. Возвращает user_id новой личности.
//
// Это примитив под перерегистрацию после потери ключей и под воссоединение
// (ДОКУМЕНТАЦИЯ/02 §5–6). Прежние сообщения остаются под прежним идентификатором
// навсегда — их подписи переписать невозможно, — а новые уходят под новым.
//
// proof — подпись прежним ключом личности. nil означает административную связку:
// человек подтвердил владение номером, но не владение ключами. Разница видна
// собеседнику (см. IdentityLink) и не должна теряться по дороге.
func (s *Store) StartNewIdentity(ctx context.Context, personID, linkedFrom string, proof []byte) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op

	// Закрываем текущую. Порядок важен: частичный уникальный индекс
	// idx_users_current_per_person не даст аккаунту иметь две текущие личности,
	// поэтому вставка до закрытия упала бы.
	if _, err := tx.Exec(ctx,
		`UPDATE users SET valid_to = now() WHERE person_id = $1 AND valid_to IS NULL`, personID); err != nil {
		return "", err
	}
	var userID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO users (person_id, linked_from, link_proof)
		VALUES ($1, $2, $3) RETURNING user_id`, personID, linkedFrom, proof).Scan(&userID); err != nil {
		return "", err
	}
	return userID, tx.Commit(ctx)
}

// PersonOfUser — аккаунт, которому принадлежит личность.
func (s *Store) PersonOfUser(ctx context.Context, userID string) (string, error) {
	var personID string
	err := s.pool.QueryRow(ctx, `SELECT person_id FROM users WHERE user_id = $1`, userID).Scan(&personID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrUserUnknown
	}
	return personID, err
}

// ErrIdentityNotFound — ни одна личность аккаунта не подписана этим ключом.
var ErrIdentityNotFound = errors.New("личность с этим ключом не найдена в цепочке аккаунта")

// FindPriorIdentity ищет в цепочке аккаунта личность, чей ключ совпадает с
// identityPub (ДОКУМЕНТАЦИЯ/02 §5, воссоединение по секретной фразе). Клиент
// выводит ключ из фразы и подписывает им челлендж — сервер должен понять, какой
// именно ПРЕЖНЕЙ личности этого же аккаунта принадлежит ключ, прежде чем принять
// связку как доказанную. Совпадение с чужим аккаунтом не считается: person_id
// в условии — это и есть граница поиска.
func (s *Store) FindPriorIdentity(ctx context.Context, personID string, identityPub []byte) (string, error) {
	var userID string
	err := s.pool.QueryRow(ctx,
		`SELECT user_id FROM users WHERE person_id = $1 AND identity_pub = $2 ORDER BY valid_from DESC LIMIT 1`,
		personID, identityPub).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrIdentityNotFound
	}
	return userID, err
}

// MoveDeviceToUser переносит устройство на новую личность аккаунта. После
// воссоединения устройство, которым человек только что доказал владение прежним
// ключом, должно писать под новой (только что заведённой, проверенной) личностью,
// а не оставаться приписанным к той, что StartNewIdentity уже закрыл.
func (s *Store) MoveDeviceToUser(ctx context.Context, deviceID, newUserID string) error {
	_, err := s.pool.Exec(ctx, `UPDATE devices SET user_id = $2 WHERE device_id = $1`, deviceID, newUserID)
	return err
}

// IdentityLink — чем подтверждена принадлежность личности аккаунту.
type IdentityLink string

const (
	// LinkRoot — первая личность аккаунта, связывать не с чем.
	LinkRoot IdentityLink = "root"
	// LinkProven — связка подписана ключом прежней личности: тот же человек доказан
	// криптографически.
	LinkProven IdentityLink = "proven"
	// LinkAdministrative — связку подтвердили только владением номером или решением
	// поддержки. Доказательства нет, и собеседник ОБЯЗАН увидеть предупреждение о
	// смене личности: иначе управляющий сервером может молча подставить постороннего
	// в чужой контакт (ДОКУМЕНТАЦИЯ/02 §4).
	LinkAdministrative IdentityLink = "administrative"
)

// Identity — к какому аккаунту относится личность и насколько это доказано.
type Identity struct {
	PersonID string       `json:"person_id"`
	Link     IdentityLink `json:"link"`
	Current  bool         `json:"current"` // текущая личность аккаунта (под ней пишут сейчас)
}

// IdentitiesOf — по списку user_id вернуть аккаунт каждой личности. Клиент так
// понимает, что несколько идентификаторов в переписке — один человек, и вправе ли
// он показать их одним контактом без предупреждения.
func (s *Store) IdentitiesOf(ctx context.Context, ids []string) (map[string]Identity, error) {
	out := make(map[string]Identity, len(ids))
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `
		SELECT user_id, person_id, linked_from IS NULL AS is_root,
		       link_proof IS NOT NULL AS proven, valid_to IS NULL AS current
		FROM users WHERE user_id = ANY($1)`, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, personID string
		var isRoot, proven, current bool
		if err := rows.Scan(&id, &personID, &isRoot, &proven, &current); err != nil {
			return nil, err
		}
		link := LinkAdministrative
		switch {
		case isRoot:
			link = LinkRoot
		case proven:
			link = LinkProven
		}
		out[id] = Identity{PersonID: personID, Link: link, Current: current}
	}
	return out, rows.Err()
}

// ErrIdentityMismatch — присланный ключ личности не совпал с установленным у аккаунта.
var ErrIdentityMismatch = errors.New("ключ личности не совпадает с установленным для аккаунта")

// SetOrCheckIdentity: если у пользователя ещё нет ключа личности — устанавливает
// присланный; если есть — требует точного совпадения (ADR-0010 §этап 3). Пустой
// identityPub — пользователь без фразы (устройство работает, восстановление недоступно).
func (s *Store) SetOrCheckIdentity(ctx context.Context, userID string, identityPub []byte) error {
	if len(identityPub) == 0 {
		return nil
	}
	var existing []byte
	if err := s.pool.QueryRow(ctx, `SELECT identity_pub FROM users WHERE user_id = $1`, userID).Scan(&existing); err != nil {
		return err
	}
	if existing == nil {
		_, err := s.pool.Exec(ctx, `UPDATE users SET identity_pub = $2 WHERE user_id = $1`, userID, identityPub)
		return err
	}
	if !bytes.Equal(existing, identityPub) {
		return ErrIdentityMismatch
	}
	return nil
}

// IdentityPub — ключ личности аккаунта устройства (nil, если не установлен).
func (s *Store) IdentityPub(ctx context.Context, userID string) ([]byte, error) {
	var pub []byte
	err := s.pool.QueryRow(ctx, `SELECT identity_pub FROM users WHERE user_id = $1`, userID).Scan(&pub)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, ErrUserUnknown
	}
	return pub, err
}
