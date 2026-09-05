// Виртуальные аккаунты: отдельные пользователи на телефоне владельца.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// VirtualLimit — сколько виртуальных аккаунтов на одного владельца.
//
// Пять (решение заказчика 2026-09-05). Без предела это спам-машина: номер один,
// аккаунтов тысяча. Считается **по владельцу, а не по номеру создания**: переданный
// виртуал уходит в счёт нового владельца и освобождает место у прежнего — иначе
// отдавший аккаунт оказался бы наказан за это местом.
const VirtualLimit = 5

var (
	// ErrTooManyVirtuals — у владельца уже пять.
	ErrTooManyVirtuals = errors.New("больше пяти виртуальных аккаунтов на владельца нельзя")

	// ErrOwnerIsVirtual — виртуальный аккаунт не может владеть другим.
	//
	// Цепочка «виртуал у виртуала» превратила бы предел в пять на каждом уровне, то
	// есть в отсутствие предела.
	ErrOwnerIsVirtual = errors.New("виртуальный аккаунт не заводит виртуальных")
)

// CreateVirtual заводит виртуальный аккаунт владельцу.
//
// Ник **обязателен**: у виртуального аккаунта нет телефона, и найти его иначе нечем.
// Ключ личности приходит от клиента — он выведен из новой фразы восстановления, которую
// сервер не видит и видеть не должен.
//
// Возвращает user_id новой личности: наружу аккаунт (person) не выходит нигде.
func (s *Store) CreateVirtual(ctx context.Context, ownerUserID, nickname string, identityPub []byte) (string, error) {
	if !ValidNickname(nickname) {
		return "", ErrNicknameBad
	}
	if len(identityPub) != 32 {
		return "", errors.New("ключ личности — 32 байта")
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Владелец: его аккаунт и то, не виртуальный ли он сам.
	var ownerPerson string
	var ownerOf *string
	err = tx.QueryRow(ctx, `
		SELECT p.person_id, p.owner_person_id
		FROM users u JOIN persons p ON p.person_id = u.person_id
		WHERE u.user_id = $1`, ownerUserID).Scan(&ownerPerson, &ownerOf)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return "", ErrUserUnknown
	} else if err != nil {
		return "", err
	}
	if ownerOf != nil {
		return "", ErrOwnerIsVirtual
	}

	// Предел считается здесь же, в транзакции: два одновременных создания иначе
	// пройдут оба и дадут шестой.
	var сколько int
	if err := tx.QueryRow(ctx, `
		SELECT count(*) FROM persons
		WHERE owner_person_id = $1 AND state <> 'archived'`, ownerPerson).Scan(&сколько); err != nil {
		return "", err
	}
	if сколько >= VirtualLimit {
		return "", ErrTooManyVirtuals
	}

	// Телефона нет — phone_bidx остаётся NULL, и частичный уникальный индекс (0019)
	// такие строки друг с другом не сравнивает.
	//
	// state = 'permanent': по неактивности виртуальный аккаунт не удаляется. Он
	// молчит по замыслу — «временный» здесь означал бы «исчезнет, пока им не
	// пользуются», а им и не пользуются подолгу.
	var personID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO persons (owner_person_id, nickname, state)
		VALUES ($1, $2, 'permanent') RETURNING person_id`,
		ownerPerson, nickname).Scan(&personID); err != nil {
		if isUniqueViolation(err) {
			return "", ErrNicknameTaken
		}
		return "", err
	}

	var userID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO users (person_id, identity_pub) VALUES ($1, $2) RETURNING user_id`,
		personID, identityPub).Scan(&userID); err != nil {
		return "", err
	}
	return userID, tx.Commit(ctx)
}

// VirtualsOf — виртуальные аккаунты владельца: их текущие личности.
func (s *Store) VirtualsOf(ctx context.Context, ownerUserID string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT u.user_id
		FROM persons p
		JOIN users u ON u.person_id = p.person_id AND u.valid_to IS NULL
		WHERE p.owner_person_id = (SELECT person_id FROM users WHERE user_id = $1)
		  AND p.state <> 'archived'
		ORDER BY p.created_at`, ownerUserID)
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

// HasPhone — есть ли у людей телефон.
//
// **Отвечает сервер, а не клиент.** «У него нет телефона» и «я его телефона не знаю»
// выглядят одинаково: телефона обычного человека клиент тоже не видит, пока тот не в
// книге. Поле вычисляется из `phone_bidx IS NULL`, а не хранится рядом: два признака об
// одном и том же однажды разошлись бы.
func (s *Store) HasPhone(ctx context.Context, ids []string) (map[string]bool, error) {
	out := make(map[string]bool, len(ids))
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `
		SELECT u.user_id, p.phone_bidx IS NOT NULL
		FROM users u JOIN persons p ON p.person_id = u.person_id
		WHERE u.user_id = ANY($1)`, ids)
	if err != nil {
		if isBadUUID(err) {
			return out, nil
		}
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		var есть bool
		if err := rows.Scan(&id, &есть); err != nil {
			return nil, err
		}
		out[id] = есть
	}
	return out, rows.Err()
}

// MarkAccountAndBelongings помечает удалённым аккаунт, его виртуальные аккаунты и всё,
// чем они владеют (ПЛАН-КОНТАКТОВ.md, §4).
//
// **Нет пользователя — нет и его собственности** (решение заказчика 2026-09-05). Группа
// и канал без хозяина были бы вечными: владельца у нас нельзя ни сменить, ни исключить
// (`owner_locked`, передачи владения нет). Теперь они не остаются вовсе.
//
// Одним действием и одним сроком: два поведения потребовали бы потом объяснять, почему
// один аккаунт исчез, а второй нет.
func (s *Store) MarkAccountAndBelongings(ctx context.Context, personID string, purgeAfterDays int) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	// Сам аккаунт и его виртуальные — одним запросом: срок обязан совпасть до секунды,
	// иначе «удалили вместе» превращается в «удалили примерно тогда же».
	if _, err := tx.Exec(ctx, `
		UPDATE persons
		SET state = 'archived', deleted_at = now(),
		    purge_after = now() + make_interval(days => $2)
		WHERE (person_id = $1 OR owner_person_id = $1) AND state <> 'archived'`,
		personID, purgeAfterDays); err != nil {
		return err
	}

	// Группы и каналы, которыми владели удаляемый или его виртуальные. Владелец
	// хранится личностью (user_id), поэтому идём через цепочку личностей аккаунта.
	if _, err := tx.Exec(ctx, `
		UPDATE groups SET deleted_at = now()
		WHERE deleted_at IS NULL AND owner_id IN (
		  SELECT u.user_id FROM users u JOIN persons p ON p.person_id = u.person_id
		  WHERE p.person_id = $1 OR p.owner_person_id = $1)`, personID); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE channels SET deleted_at = now()
		WHERE deleted_at IS NULL AND owner_id IN (
		  SELECT u.user_id FROM users u JOIN persons p ON p.person_id = u.person_id
		  WHERE p.person_id = $1 OR p.owner_person_id = $1)`, personID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
