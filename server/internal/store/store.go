// Package store — доступ к PostgreSQL (pgx). Сервер хранит только ciphertext
// и обёртки; ключей и открытого текста здесь нет по построению.
//
// ── ЧТО В ЭТОМ ФАЙЛЕ И ЧЕГО В НЁМ НЕТ ───────────────────────────────────────
//
// Здесь сам тип, подключение и миграции — то, что относится к хранилищу как
// таковому. Запросы разложены по темам: users.go, identities.go, devices.go,
// messages.go, group_keys.go, media.go и остальные.
//
// До 2026-08-25 в store.go лежали сорок три метода из семи тем сразу: SMS-коды,
// личности, устройства, сообщения, ключи групп, медиа и миграции. Тип от этого
// не страдал — страдал файл: две правки по разным темам сходились в одном месте
// и ждали друг друга на merge.
//
// **Тип остался один, и это намеренно.** База одна, пул один, транзакция одна;
// «репозиторий групп» и «репозиторий звонков» разрезали бы операцию, которой
// нужны обе таблицы разом, и размазали бы общий пул по нескольким владельцам.
// Сужается не хранилище, а то, что доходит до потребителя: каждый registrar в
// internal/api объявляет свой узкий интерфейс, а компилятор проверяет
// соответствие строкой var _ ChannelStore = (*store.Store)(nil).
package store

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"sort"
	"strings"

	"github.com/jackc/pgx/v5/pgxpool"

	"tima/server/internal/pii"
)

type Store struct {
	pool *pgxpool.Pool
	// pii шифрует персональные поля. Единственная точка, через которую код
	// хранилища обращается к ключу: переезд ключа к внешнему держателю — замена
	// реализации здесь, а не правки по всему пакету (план рефакторинга §2).
	pii *pii.Cipher
}

func New(ctx context.Context, databaseURL string, cipher *pii.Cipher) (*Store, error) {
	if cipher == nil {
		return nil, errors.New("store: нужен ключ персональных данных (internal/pii)")
	}
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("подключение к PostgreSQL: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("ping PostgreSQL: %w", err)
	}
	return &Store{pool: pool, pii: cipher}, nil
}

func (s *Store) Close() { s.pool.Close() }

// Migrate применяет *.sql из fsys по порядку имён; выполненные помнит в schema_migrations.
func (s *Store) Migrate(ctx context.Context, fsys fs.FS) error {
	if _, err := s.pool.Exec(ctx,
		`CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	names, err := fs.Glob(fsys, "*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	for _, name := range names {
		var exists bool
		if err := s.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE name=$1)`, name).Scan(&exists); err != nil {
			return err
		}
		if exists {
			continue
		}
		sql, err := fs.ReadFile(fsys, name)
		if err != nil {
			return err
		}
		tx, err := s.pool.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, string(sql)); err != nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("миграция %s: %w", name, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO schema_migrations(name) VALUES($1)`, name); err != nil {
			_ = tx.Rollback(ctx)
			return err
		}
		if err := tx.Commit(ctx); err != nil {
			return err
		}
	}
	return nil
}

// keepForTests — что ResetForTests обязан оставить.
//
// schema_migrations — журнал применённых миграций: стерев его, следующий Migrate
// применил бы всё заново поверх существующих таблиц.
//
// retention_policy — сроки хранения, засеянные самими миграциями. Это не данные
// прогона, а часть схемы; пустая таблица означала бы «сроков нет», и worker_test
// проверял бы выдуманное поведение.
var keepForTests = []string{"schema_migrations", "retention_policy"}

// ResetForTests очищает таблицы (только интеграционные тесты; в бою не вызывается).
//
// Отказывается работать с базой, имя которой не заканчивается на _test. Ниже
// TRUNCATE по всем таблицам сразу, и до этой проверки единственным, что отделяло
// его от боевых данных, была переменная окружения: забытый или опечатанный
// TIMA_TEST_DATABASE_URL стоил бы мессенджеру всей истории.
//
// **Список таблиц спрашивается у базы, а не пишется здесь руками.** Так было до
// 2026-08-26, и перечисление ломалось в обе стороны:
//
//   - таблица, которой в перечне нет, между прогонами НЕ чистится. chat_states
//     появилась миграцией 0024 и в перечень не попала: строки копились молча, и
//     заметить это можно было только по тесту, который проходит в одиночку и
//     падает вторым;
//   - **лишняя таблица в томе роняет весь пакет**. TRUNCATE отказывается работать,
//     если на очищаемую таблицу ссылается внешним ключом таблица, которой в списке
//     нет. Старый том Postgres с таблицами от прежней схемы (communities, stories)
//     давал отказ в setup, а значит t.Fatal в каждом тесте: 71 падение, из которых
//     ни одно не дошло до своего сценария. Выглядит как «изменение сломало всё»,
//     хотя не выполнилось вообще ничего.
//
// Перечисление снимает оба случая разом: чистится ровно то, что в базе есть.
func (s *Store) ResetForTests(ctx context.Context) error {
	if db := s.pool.Config().ConnConfig.Database; !strings.HasSuffix(db, "_test") {
		return fmt.Errorf("store: ResetForTests отказано — база %q не заканчивается на _test", db)
	}
	rows, err := s.pool.Query(ctx, `
		SELECT quote_ident(tablename) FROM pg_tables
		WHERE schemaname = current_schema() AND NOT tablename = ANY($1)
		ORDER BY tablename`, keepForTests)
	if err != nil {
		return fmt.Errorf("store: ResetForTests — список таблиц: %w", err)
	}
	var tables []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			rows.Close()
			return fmt.Errorf("store: ResetForTests — список таблиц: %w", err)
		}
		tables = append(tables, name)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return fmt.Errorf("store: ResetForTests — список таблиц: %w", err)
	}
	if len(tables) == 0 {
		// Пустая база — миграции ещё не применялись. Чистить нечего, и это не ошибка.
		return nil
	}
	// Одним оператором: TRUNCATE нескольких таблиц сразу разрешает взаимные
	// внешние ключи между ними, а по одной — нет.
	if _, err := s.pool.Exec(ctx, "TRUNCATE "+strings.Join(tables, ", ")); err != nil {
		return fmt.Errorf("store: ResetForTests — TRUNCATE %d таблиц: %w", len(tables), err)
	}
	return nil
}
