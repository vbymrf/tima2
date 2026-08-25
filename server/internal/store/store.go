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

// ResetForTests очищает таблицы (только интеграционные тесты; в бою не вызывается).
//
// Отказывается работать с базой, имя которой не заканчивается на _test. Ниже
// TRUNCATE по всем таблицам сразу, и до этой проверки единственным, что отделяло
// его от боевых данных, была переменная окружения: забытый или опечатанный
// TIMA_TEST_DATABASE_URL стоил бы мессенджеру всей истории.
func (s *Store) ResetForTests(ctx context.Context) error {
	if db := s.pool.Config().ConnConfig.Database; !strings.HasSuffix(db, "_test") {
		return fmt.Errorf("store: ResetForTests отказано — база %q не заканчивается на _test", db)
	}
	// persons и escrow_keys — тоже тестовые данные. Без них persons копил бы
	// аккаунты между прогонами (и держал номера занятыми), а escrow_keys ловил бы
	// конфликт идентификаторов с анклавом, который в каждом тесте поднимается
	// заново и начинает нумерацию с единицы.
	// retention_policy НЕ трогаем: там строки, засеянные миграцией.
	_, err := s.pool.Exec(ctx, `TRUNCATE personal_messages, personal_message_keys, personal_message_backup, device_link_sessions, devices, users, persons, escrow_keys, sms_codes, media_objects, group_key_history, group_wrapped_keys, groups, memberships, group_messages, device_events, sync_cursors, gc_state, channels, channel_subscriptions, channel_posts, calls, call_participants, voice_rooms, voice_speakers`)
	return err
}
