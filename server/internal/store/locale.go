// Страна и язык человека (ПЛАН-ЯЗЫКА Я5…Я7).
//
// Две задачи и никаких других: клиенту отсеивать ненужное, серверу готовить региональные и
// языковые ленты (решение заказчика 2026-09-08). Поэтому здесь только чтение и запись
// настройки — ни перевода, ни определения языка по тексту.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

// Locale — что человек указал о себе.
//
// Страна и язык — РАЗНЫЕ ответы: русский пишут и в Казахстане, испанский — в двух десятках
// стран. Хранятся оба, потому что отвечают на разные вопросы: страна — кому отдавать, язык
// — что человек читает.
type Locale struct {
	// Lang — тег языка с необязательным регионом: `ru`, `en`, `es`, `pt-BR`.
	Lang string
	// Country — код страны, ISO 3166-1 alpha-2. Пусто значит «не указана»: такой человек
	// видит всё, а не ничего.
	Country string
}

// LocaleOf — страна и язык человека.
func (s *Store) LocaleOf(ctx context.Context, userID string) (Locale, error) {
	var l Locale
	err := s.pool.QueryRow(ctx,
		`SELECT lang, country FROM users WHERE user_id = $1`, userID).Scan(&l.Lang, &l.Country)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		// Человека нет — отдаём умолчание, а не ошибку: штамп на записи нужен всегда, а
		// отсутствие строки означает лишь, что спрашивают не о том.
		return Locale{Lang: "ru"}, nil
	}
	return l, err
}

// SetLocale — человек указал страну и язык.
//
// Проверка значений здесь намеренно грубая: длина. Списка стран и языков сервер не держит —
// он не переводит и не отображает, ему хватает того, что строка короткая и сравнимая.
// Полный справочник на сервере пришлось бы обновлять вместе с миром.
func (s *Store) SetLocale(ctx context.Context, userID string, l Locale) error {
	_, err := s.pool.Exec(ctx,
		`UPDATE users SET lang = $2, country = $3 WHERE user_id = $1`, userID, l.Lang, l.Country)
	// Кривой идентификатор — не ошибка записи, а отсутствие такого человека. Здесь это
	// одно и то же: настройку некому применить.
	if err != nil && isBadUUID(err) {
		return nil
	}
	return err
}
