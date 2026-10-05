package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// Перерегистрация при компрометации и удаление личности (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ
// ДУ9, ДУ11; устройство — §2г). С — прежняя личность, Н — заведённая перерегистрацией.

// Rereg — процесс перерегистрации аккаунта.
type Rereg struct {
	ID             string
	PersonID       string
	OldUserID      string
	NewUserID      string
	StartedAt      time.Time
	WindowFrom     time.Time
	WindowTo       time.Time
	ClaimAt        *time.Time
	NewConfirmedAt *time.Time
	OldConfirmedAt *time.Time
	Round          int
	WindowNoticed  int
}

// Disputed — подана встречная заявка «Аккаунт украден».
func (r Rereg) Disputed() bool { return r.ClaimAt != nil }

// ErrNoRereg — открытого процесса нет.
var ErrNoRereg = errors.New("перерегистрации нет")

// ErrReregOpen — у аккаунта уже идёт перерегистрация.
var ErrReregOpen = errors.New("перерегистрация уже идёт")

// Причины отключения устройства — по ним экран отключения говорит человеку, что случилось.
const (
	RevokedReregistered = "reregistered"
	RevokedDisputed     = "disputed"
	// Личность удаляется (ДУ11): Н, чью перерегистрацию не подтвердили, и С, чью подтвердили.
	RevokedReregNotConfirmed = "rereg_not_confirmed"
	RevokedReregConfirmed    = "rereg_confirmed"
)

const reregColumns = `id, person_id, old_user_id, new_user_id, started_at, window_from, window_to,
	claim_at, new_confirmed_at, old_confirmed_at, round, window_noticed`

func scanRereg(row pgx.Row) (Rereg, error) {
	var r Rereg
	err := row.Scan(&r.ID, &r.PersonID, &r.OldUserID, &r.NewUserID, &r.StartedAt, &r.WindowFrom, &r.WindowTo,
		&r.ClaimAt, &r.NewConfirmedAt, &r.OldConfirmedAt, &r.Round, &r.WindowNoticed)
	if errors.Is(err, pgx.ErrNoRows) {
		return Rereg{}, ErrNoRereg
	}
	return r, err
}

// revokeWithoutIdentityKey — отключить устройства личностей, которые заверил кто-то другой
// (ПК и всё, что заверил ключ подписи устройств другого телефона): «выданные заверения
// отзываются» (Р34). Работают дальше «устройства с ключом личности»: заверенные ключом
// личности (ПК, вошедший по фразе) и телефоны, заверившие себя своим же ключом подписи
// устройств, — так заверяет себя телефон, вошедший по фразе (ДУ1, ДУ2). Ключи подписи
// отключённых устройств снимаются вместе с ними.
//
// Живьём 2026-10-05: первая версия оставляла только `cert_by = 'identity'` и отключила телефоны
// обеих сторон — у телефона это всегда `ask`.
func revokeWithoutIdentityKey(ctx context.Context, tx pgx.Tx, userIDs []string, reason string) error {
	_, err := tx.Exec(ctx, `
		WITH gone AS (
			UPDATE devices d SET revoked_at = now(), revoked_reason = $2
			WHERE d.user_id = ANY($1) AND d.revoked_at IS NULL
			  AND NOT (d.cert_by = 'identity' OR (d.cert_by = 'ask' AND EXISTS (
			      SELECT 1 FROM account_signing_keys k WHERE k.ask_id = d.cert_ask_id AND k.device_id = d.device_id)))
			RETURNING d.device_id)
		UPDATE account_signing_keys SET revoked_at = now()
		WHERE device_id IN (SELECT device_id FROM gone) AND revoked_at IS NULL`, userIDs, reason)
	return err
}

// StartRereg заводит процесс после того, как Н стала текущей: ожидание wait, окно window.
// Устройства С без ключа личности отключаются, её ключи подписи устройств снимаются.
func (s *Store) StartRereg(ctx context.Context, personID, oldUserID, newUserID string, wait, window time.Duration) (Rereg, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Rereg{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op
	from := time.Now().Add(wait)
	r, err := scanRereg(tx.QueryRow(ctx, `
		INSERT INTO reregistrations (person_id, old_user_id, new_user_id, window_from, window_to)
		VALUES ($1, $2, $3, $4, $5)
		RETURNING `+reregColumns, personID, oldUserID, newUserID, from, from.Add(window)))
	if err != nil {
		if isUniqueViolation(err) {
			return Rereg{}, ErrReregOpen
		}
		return Rereg{}, err
	}
	if err := revokeWithoutIdentityKey(ctx, tx, []string{oldUserID}, RevokedReregistered); err != nil {
		return Rereg{}, err
	}
	return r, tx.Commit(ctx)
}

// ReregOfUser — открытый процесс аккаунта, которому принадлежит личность.
func (s *Store) ReregOfUser(ctx context.Context, userID string) (Rereg, error) {
	return scanRereg(s.pool.QueryRow(ctx, `
		SELECT `+reregColumns+` FROM reregistrations
		WHERE closed_at IS NULL AND person_id = (SELECT person_id FROM users WHERE user_id = $1)`, userID))
}

// ClaimRereg — «Аккаунт украден» (Р34): спор. Устройства обеих сторон без ключа личности
// отключаются, ключи подписи устройств снимаются; заверять до исхода не даёт ReregOfUser.
func (s *Store) ClaimRereg(ctx context.Context, r Rereg) (Rereg, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Rereg{}, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op
	out, err := scanRereg(tx.QueryRow(ctx, `
		UPDATE reregistrations SET claim_at = now()
		WHERE id = $1 AND closed_at IS NULL AND claim_at IS NULL
		RETURNING `+reregColumns, r.ID))
	if err != nil {
		return Rereg{}, err
	}
	if err := revokeWithoutIdentityKey(ctx, tx, []string{r.OldUserID, r.NewUserID}, RevokedDisputed); err != nil {
		return Rereg{}, err
	}
	return out, tx.Commit(ctx)
}

// ConfirmRereg — подтверждение стороны в окне: newSide — от Н, иначе от С.
func (s *Store) ConfirmRereg(ctx context.Context, id string, newSide bool) (Rereg, error) {
	column := "old_confirmed_at"
	if newSide {
		column = "new_confirmed_at"
	}
	return scanRereg(s.pool.QueryRow(ctx, `
		UPDATE reregistrations SET `+column+` = now()
		WHERE id = $1 AND closed_at IS NULL AND now() BETWEEN window_from AND window_to
		RETURNING `+reregColumns, id))
}

// ReregsDue — процессы, у которых пора открыть окно (извещение) или кончилось окно (исход).
func (s *Store) ReregsDue(ctx context.Context, now time.Time) ([]Rereg, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT `+reregColumns+` FROM reregistrations
		WHERE closed_at IS NULL AND (window_to <= $1 OR (window_from <= $1 AND window_noticed < round))
		ORDER BY window_from LIMIT 200`, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Rereg
	for rows.Next() {
		r, err := scanRereg(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// MarkReregWindowNoticed — извещение «окно открылось» за текущий круг ушло.
func (s *Store) MarkReregWindowNoticed(ctx context.Context, id string, round int) error {
	_, err := s.pool.Exec(ctx, `UPDATE reregistrations SET window_noticed = $2 WHERE id = $1`, id, round)
	return err
}

// Исходы перерегистрации (Р34).
const (
	ReregExtended = "extended" // подтвердили оба — новое ожидание и окно
	ReregNewWins  = "new"      // подтвердила только Н — удаляется С
	ReregOldWins  = "old"      // только С или никто — удаляется Н, текущей снова С
)

// ResolveRereg применяет исход по подтверждениям за окно и возвращает его. Проигравшая личность
// — на удаление (ДУ11): её устройства отключаются сразу, сама она живёт до delete_at (Р39).
func (s *Store) ResolveRereg(ctx context.Context, r Rereg, wait, window, deleteAfter time.Duration) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op
	newOK, oldOK := r.NewConfirmedAt != nil, r.OldConfirmedAt != nil
	deleteAt := time.Now().Add(deleteAfter)
	outcome := ReregOldWins
	switch {
	case newOK && oldOK:
		outcome = ReregExtended
		from := r.WindowTo.Add(wait)
		if _, err := tx.Exec(ctx, `
			UPDATE reregistrations SET window_from = $2, window_to = $3, round = round + 1,
			       new_confirmed_at = NULL, old_confirmed_at = NULL
			WHERE id = $1`, r.ID, from, from.Add(window)); err != nil {
			return "", err
		}
		return outcome, tx.Commit(ctx)
	case newOK:
		outcome = ReregNewWins
		if err := scheduleDelete(ctx, tx, r.OldUserID, deleteAt, RevokedReregConfirmed); err != nil {
			return "", err
		}
	default:
		// Порядок важен: частичный уникальный индекс «одна текущая личность на аккаунт».
		if _, err := tx.Exec(ctx, `UPDATE users SET valid_to = COALESCE(valid_to, now()) WHERE user_id = $1`, r.NewUserID); err != nil {
			return "", err
		}
		if err := scheduleDelete(ctx, tx, r.NewUserID, deleteAt, RevokedReregNotConfirmed); err != nil {
			return "", err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM identity_group_claims WHERE user_id = $1`, r.NewUserID); err != nil {
			return "", err
		}
		if _, err := tx.Exec(ctx, `UPDATE users SET valid_to = NULL WHERE user_id = $1`, r.OldUserID); err != nil {
			return "", err
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE reregistrations SET outcome = $2, closed_at = now() WHERE id = $1`, r.ID, outcome); err != nil {
		return "", err
	}
	return outcome, tx.Commit(ctx)
}

// scheduleDelete — личность на удаление (Р37, Р39): все устройства отключаются сразу, ключи
// подписи устройств снимаются, удаление — в deleteAt.
func scheduleDelete(ctx context.Context, tx pgx.Tx, userID string, deleteAt time.Time, reason string) error {
	if _, err := tx.Exec(ctx, `
		UPDATE devices SET revoked_at = now(), revoked_reason = $2
		WHERE user_id = $1 AND revoked_at IS NULL`, userID, reason); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE account_signing_keys SET revoked_at = now() WHERE user_id = $1 AND revoked_at IS NULL`, userID); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `UPDATE users SET delete_at = $2 WHERE user_id = $1`, userID, deleteAt)
	return err
}

// DeletedIdentity — личность, удаление которой состоялось, и группы, из которых она вышла.
type DeletedIdentity struct {
	UserID string
	Groups []string
}

// FinishIdentityDeletes — удаление личностей, чей срок вышел (Р37): отметка deleted_at и выход
// из групп. Сообщения остаются у собеседников с пометкой (Р49) — их сервер не трогает.
func (s *Store) FinishIdentityDeletes(ctx context.Context, now time.Time) ([]DeletedIdentity, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op
	rows, err := tx.Query(ctx, `
		UPDATE users SET deleted_at = now()
		WHERE delete_at <= $1 AND deleted_at IS NULL
		RETURNING user_id`, now)
	if err != nil {
		return nil, err
	}
	var out []DeletedIdentity
	for rows.Next() {
		var d DeletedIdentity
		if err := rows.Scan(&d.UserID); err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, d)
	}
	rows.Close()
	for i := range out {
		gr, err := tx.Query(ctx, `
			UPDATE memberships SET left_at = now()
			WHERE target_type = 'group' AND user_id = $1 AND left_at IS NULL
			RETURNING target_id::text`, out[i].UserID)
		if err != nil {
			return nil, err
		}
		for gr.Next() {
			var g string
			if err := gr.Scan(&g); err != nil {
				gr.Close()
				return nil, err
			}
			out[i].Groups = append(out[i].Groups, g)
		}
		gr.Close()
	}
	return out, tx.Commit(ctx)
}

// RevokedDevice — отключённое устройство: его ключ подписи (по нему оно доказывает, что это оно),
// почему отключено и, если его личность удаляется, когда (ДУ9, ДУ11). ErrDeviceUnknown — нет
// такого отключённого устройства у этой личности.
type RevokedDevice struct {
	SigningPub []byte
	Reason     string
	DeleteAt   *time.Time
}

func (s *Store) RevokedDevice(ctx context.Context, deviceID, userID string) (RevokedDevice, error) {
	var d RevokedDevice
	err := s.pool.QueryRow(ctx, `
		SELECT d.signing_pub, d.revoked_reason, u.delete_at FROM devices d JOIN users u ON u.user_id = d.user_id
		WHERE d.device_id = $1 AND d.user_id = $2 AND d.revoked_at IS NOT NULL`, deviceID, userID).Scan(&d.SigningPub, &d.Reason, &d.DeleteAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return RevokedDevice{}, ErrDeviceUnknown
	}
	return d, err
}
