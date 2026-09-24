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

// ErrNicknameLocked — ник этой личностью уже задан, второй раз нельзя (0050;
// решение заказчика 2026-09-15). Право на смену возвращает только новая личность —
// «Начать заново» с другой фразой. Не воспользовалась — прежний ник остаётся.
var ErrNicknameLocked = errors.New("ник этой личностью уже задан")

// Те же границы, что в CHECK миграции 0037. Проверка стоит дважды намеренно:
// база защищает от второго приложения, код — отвечает человеку словами, а не
// ошибкой драйвера.
var nicknameRe = regexp.MustCompile(`^[A-Za-z0-9_]{10,20}$`)

// ValidNickname — годится ли ник. Вынесена, чтобы ручка отвечала «нет» до похода
// в базу: занятость спрашивают на каждую букву, и гонять запрос ради заведомо
// негодного значения незачем.
func ValidNickname(nick string) bool { return nicknameRe.MatchString(nick) }

// SetNickname — занять ник. Один раз на личность.
//
// Сравнение без учёта регистра делает частичный уникальный индекс по lower(nickname)
// (0037). Хранится ник как введён: человек видит своё написание.
//
// Замок — в самом UPDATE, а не отдельным SELECT перед ним: две одновременные попытки
// одной личности иначе прошли бы обе. Условие пропускает, когда ник задавала другая
// личность (nickname_set_by иной) или не задавал никто (NULL); свою же личность —
// нет. Ноль строк при живом аккаунте и означает замок.
func (s *Store) SetNickname(ctx context.Context, userID, nick string) error {
	if !ValidNickname(nick) {
		return ErrNicknameBad
	}
	tag, err := s.pool.Exec(ctx, `
		UPDATE persons SET nickname = $2, nickname_set_by = $1, profile_rev = profile_rev + 1
		WHERE person_id = (SELECT person_id FROM users WHERE user_id = $1)
		  AND (nickname_set_by IS NULL OR nickname_set_by <> $1)`, userID, nick)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return ErrNicknameTaken
	}
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNicknameLocked
	}
	return nil
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

// NicknameHit — одна строка выдачи поиска: кто и под каким ником.
type NicknameHit struct {
	UserID   string
	Nickname string
}

// MinNicknameQuery — короче не ищем (Л10, §6 плана).
//
// Три знака — не аккуратность, а часть барьера от спама: по одной-двум буквам выдачи
// не бывает, бывает выгрузка справочника по алфавиту.
const MinNicknameQuery = 3

// SearchNicknames — точное совпадение либо похожие (Л10).
//
// ── ПРАВИЛО ИСПОЛНЯЕТ СЕРВЕР, А НЕ ДВА ПОХОДА КЛИЕНТА ───────────────────────
//
// Решение заказчика 2026-09-24: точное совпадение первым; нашлось как есть — дальше
// не ищем; не нашлось — похожие. Один запрос, потому что круг экономится, а правило
// лежит в одном месте: два похода клиента разошлись бы порядком у разных клиентов.
//
// Похожие — по вхождению подстроки, а не по началу строки: человек помнит середину
// ника не реже, чем начало. Цена названа в плане прямо: это перебор пространства
// имён, и держат его пределы — минимум знаков, предел выдачи и частота.
//
// Порядок: сначала те, у кого ник НАЧИНАЕТСЯ с запроса, потом остальные. Внутри —
// по длине и по алфавиту: короткий ближе к запросу, чем длинный с тем же куском.
func (s *Store) SearchNicknames(ctx context.Context, q string, limit int) ([]NicknameHit, error) {
	q = strings.TrimSpace(q)
	if len([]rune(q)) < MinNicknameQuery {
		return nil, ErrNicknameBad
	}
	// Запрос — часть ника, а не ник: границы длины к нему не применимы, но набор
	// знаков тот же. Иначе «%» и «_» из запроса стали бы шаблоном LIKE.
	if !nicknamePartRe.MatchString(q) {
		return nil, ErrNicknameBad
	}
	if limit <= 0 || limit > MaxNicknameHits {
		limit = MaxNicknameHits
	}
	// Точное — первым и в одиночку: нашлось как есть, значит искали именно его.
	if ValidNickname(q) {
		if id, err := s.FindUserByNickname(ctx, q); err == nil {
			return []NicknameHit{{UserID: id, Nickname: q}}, nil
		} else if !errors.Is(err, ErrUserUnknown) {
			return nil, err
		}
	}
	rows, err := s.pool.Query(ctx, `
		SELECT u.user_id, p.nickname FROM persons p
		JOIN users u ON u.person_id = p.person_id AND u.valid_to IS NULL
		WHERE p.nickname IS NOT NULL AND lower(p.nickname) LIKE '%' || lower($1) || '%'
		ORDER BY (lower(p.nickname) LIKE lower($1) || '%') DESC, length(p.nickname), lower(p.nickname)
		LIMIT $2`, q, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]NicknameHit, 0, limit)
	for rows.Next() {
		var hit NicknameHit
		if err := rows.Scan(&hit.UserID, &hit.Nickname); err != nil {
			return nil, err
		}
		out = append(out, hit)
	}
	return out, rows.Err()
}

// MaxNicknameHits — предел выдачи (Л10, §6). «Нашлось много» — не список, а повод
// дописать ещё букву.
const MaxNicknameHits = 10

// Часть ника: тот же набор знаков, но без границ длины — запрос короче ника.
var nicknamePartRe = regexp.MustCompile(`^[A-Za-z0-9_]{1,20}$`)

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
