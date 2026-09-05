// Ник — второе имя аккаунта, по которому его находят посторонние.
package store

import (
	"context"
	"errors"
	"regexp"
	"strings"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// ErrNicknameTaken — ник уже занят другим аккаунтом. Освобождения нет: занятый
// однажды остаётся за аккаунтом и после смены (решение заказчика 2026-09-05).
// Освободившийся достался бы другому человеку, и старые упоминания начали бы
// указывать не на того.
var ErrNicknameTaken = errors.New("ник занят")

// ErrNicknameBad — ник не проходит границы: 10…20 знаков, латиница, цифры,
// подчёркивание. Короче десяти зарезервировано.
var ErrNicknameBad = errors.New("ник не проходит границы")

// Те же границы, что в CHECK миграции 0037. Проверка стоит дважды намеренно:
// база защищает от второго приложения, код — отвечает человеку словами, а не
// ошибкой драйвера.
var nicknameRe = regexp.MustCompile(`^[A-Za-z0-9_]{10,20}$`)

// ValidNickname — годится ли ник. Вынесена, чтобы ручка отвечала «нет» до похода
// в базу: занятость спрашивают на каждую букву, и гонять запрос ради заведомо
// негодного значения незачем.
func ValidNickname(nick string) bool { return nicknameRe.MatchString(nick) }

// SetNickname — занять ник или сменить свой.
//
// Сравнение без учёта регистра делает частичный уникальный индекс по lower(nickname)
// (0037). Хранится ник как введён: человек видит своё написание.
func (s *Store) SetNickname(ctx context.Context, userID, nick string) error {
	if !ValidNickname(nick) {
		return ErrNicknameBad
	}
	_, err := s.pool.Exec(ctx, `
		UPDATE persons SET nickname = $2
		WHERE person_id = (SELECT person_id FROM users WHERE user_id = $1)`, userID, nick)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return ErrNicknameTaken
	}
	return err
}

// NicknameFree — свободен ли ник. Отвечает и о своём собственном: занявший его
// человек спрашивает о нём же, открывая экран правки.
func (s *Store) NicknameFree(ctx context.Context, nick string) (bool, error) {
	if !ValidNickname(nick) {
		return false, ErrNicknameBad
	}
	var занят bool
	err := s.pool.QueryRow(ctx,
		`SELECT EXISTS(SELECT 1 FROM persons WHERE lower(nickname) = lower($1))`, nick).Scan(&занят)
	return !занят, err
}

// FindUserByNickname — чей это ник. Возвращает текущую личность аккаунта, как и
// поиск по номеру: пишет человек под ней, а ник принадлежит аккаунту.
//
// Совпадение точное (с точностью до регистра), а не по началу строки: подстрочный
// поиск по нику — это перебор каталога людей, а не поиск знакомого.
func (s *Store) FindUserByNickname(ctx context.Context, nick string) (string, error) {
	if !ValidNickname(nick) {
		return "", ErrNicknameBad
	}
	var userID string
	err := s.pool.QueryRow(ctx, `
		SELECT u.user_id FROM persons p
		JOIN users u ON u.person_id = p.person_id AND u.valid_to IS NULL
		WHERE lower(p.nickname) = lower($1)`, nick).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrUserUnknown
	}
	return userID, err
}

// Nicknames — ники перечисленных людей. Пустых в ответе нет: у кого ника нет,
// того нет и в карте — иначе клиент не отличит «ника нет» от «сервер не ответил».
func (s *Store) Nicknames(ctx context.Context, ids []string) (map[string]string, error) {
	out := make(map[string]string, len(ids))
	if len(ids) == 0 {
		return out, nil
	}
	rows, err := s.pool.Query(ctx, `
		SELECT u.user_id, p.nickname FROM users u
		JOIN persons p ON p.person_id = u.person_id
		WHERE u.user_id = ANY($1) AND p.nickname IS NOT NULL`, ids)
	if err != nil {
		if isBadUUID(err) {
			return out, nil
		}
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id, nick string
		if err := rows.Scan(&id, &nick); err != nil {
			return nil, err
		}
		out[id] = strings.TrimSpace(nick)
	}
	return out, rows.Err()
}
