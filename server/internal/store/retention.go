package store

// Состояния аккаунта и сроки хранения (миграция 0021, ДОКУМЕНТАЦИЯ/04).
//
// Удаление здесь всегда двухшаговое: пометка и, отдельным событием, физическое
// стирание. Между ними стоит гейт по escrow — стирать содержимое можно только
// после того, как уничтожен ключ эпохи, к которой оно относится.

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
)

// AccountState — состояние аккаунта.
type AccountState string

const (
	// StateTemporary — без телефона и почты. Восстановления нет, удаляется по неактивности.
	StateTemporary AccountState = "temporary"
	// StatePermanent — телефон подтверждён. По неактивности НЕ удаляется никогда.
	StatePermanent AccountState = "permanent"
	// StateArchived — помечен удалённым: данные ещё есть, доступа уже нет.
	StateArchived AccountState = "archived"
)

// RetentionDays — срок политики в днях. Сроки живут в базе, а не в коде: смена
// требования должна быть правкой строки, а не пересборкой (решение 3 плана).
func (s *Store) RetentionDays(ctx context.Context, name string) (int, error) {
	var days int
	err := s.pool.QueryRow(ctx, `SELECT days FROM retention_policy WHERE name = $1`, name).Scan(&days)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, fmt.Errorf("политика хранения %q не задана", name)
	}
	return days, err
}

// SetRetentionDays меняет срок политики.
func (s *Store) SetRetentionDays(ctx context.Context, name string, days int) error {
	tag, err := s.pool.Exec(ctx,
		`UPDATE retention_policy SET days = $2, updated_at = now() WHERE name = $1`, name, days)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return fmt.Errorf("политика хранения %q не задана", name)
	}
	return nil
}

// MarkAccountDeleted переводит аккаунт в архив и назначает срок стирания.
//
// Сама запись остаётся: помеченный аккаунт недоступен ни владельцу, ни
// собеседникам, но может понадобиться по юридически обязывающему запросу в
// пределах установленного срока.
func (s *Store) MarkAccountDeleted(ctx context.Context, personID string, purgeAfterDays int) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE persons
		SET state = 'archived', deleted_at = now(),
		    purge_after = now() + make_interval(days => $2)
		WHERE person_id = $1 AND state <> 'archived'`, personID, purgeAfterDays)
	return err
}

// TouchAccount отмечает активность. По ней считается неактивность временных
// аккаунтов; постоянных это не касается, они по сроку не удаляются вовсе.
func (s *Store) TouchAccount(ctx context.Context, personID string) error {
	_, err := s.pool.Exec(ctx,
		`UPDATE persons SET last_active_at = now() WHERE person_id = $1`, personID)
	return err
}

// PromoteAccount делает аккаунт постоянным: телефон подтверждён, восстановление есть.
// Переход только в эту сторону — подтверждённый номер не «протухает» от неиспользования.
func (s *Store) PromoteAccount(ctx context.Context, personID string) error {
	_, err := s.pool.Exec(ctx,
		`UPDATE persons SET state = 'permanent' WHERE person_id = $1 AND state = 'temporary'`, personID)
	return err
}

// InactiveTemporaryAccounts — временные аккаунты, молчавшие дольше срока.
//
// ТОЛЬКО временные. Постоянный аккаунт по неактивности не удаляется никогда:
// иначе человек в отпуске или в больнице терял бы историю, ничего для этого не
// сделав (ДОКУМЕНТАЦИЯ/04 §2).
func (s *Store) InactiveTemporaryAccounts(ctx context.Context, inactiveDays, limit int) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT person_id FROM persons
		WHERE state = 'temporary' AND last_active_at < now() - make_interval(days => $1)
		ORDER BY last_active_at LIMIT $2`, inactiveDays, limit)
	if err != nil {
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

// PurgeableAccounts — архивные аккаунты, у которых вышла выдержка И уничтожены
// escrow-ключи всех их сообщений.
//
// Гейт по escrow — не перестраховка. Пока ключ эпохи жив, содержимое обязано
// храниться: срок хранения по закону ещё не истёк. Стирать раньше — нарушать его.
func (s *Store) PurgeableAccounts(ctx context.Context, now time.Time, limit int) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT p.person_id
		FROM persons p
		WHERE p.state = 'archived' AND p.purge_after IS NOT NULL AND p.purge_after <= $1
		  AND NOT EXISTS (
		      -- хотя бы один живой ключ эпохи в чатах, где писала любая личность аккаунта
		      SELECT 1
		      FROM users u
		      JOIN personal_messages m ON m.sender_id = u.user_id
		      JOIN escrow_keys k ON k.chat_id = m.chat_id
		      WHERE u.person_id = p.person_id AND k.destroy_at > $1
		  )
		ORDER BY p.purge_after LIMIT $2`, now, limit)
	if err != nil {
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

// PurgeMessageContent стирает содержимое сообщений, чьи ключи эпох уже уничтожены.
// Строка остаётся: метаданные (кто, кому, когда, какого типа) не удаляются никогда
// — это отдельная плоскость с отдельным сроком (план §0).
func (s *Store) PurgeMessageContent(ctx context.Context, now time.Time, limit int) (int64, error) {
	tag, err := s.pool.Exec(ctx, `
		UPDATE personal_messages m
		SET encrypted_payload = ''::bytea,
		    escrow_wrapped_key = ''::bytea,
		    escrow_mlkem_ct = ''::bytea,
		    ratchet_envelope = NULL
		WHERE (m.chat_id, m.message_id) IN (
		    SELECT m2.chat_id, m2.message_id
		    FROM personal_messages m2
		    JOIN escrow_keys k ON k.id = m2.escrow_key_version
		    WHERE k.destroy_at <= $1 AND octet_length(m2.encrypted_payload) > 0
		    LIMIT $2
		)`, now, limit)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}
