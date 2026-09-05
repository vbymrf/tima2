// Передача виртуального аккаунта другому человеку.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

var (
	// ErrNotYourVirtual — передавать можно только свой виртуальный аккаунт.
	ErrNotYourVirtual = errors.New("это не ваш виртуальный аккаунт")

	// ErrTransferGone — код не найден, погашен или просрочен.
	//
	// Один ответ на три случая намеренно: различать их значило бы сообщать
	// предъявителю, существовала ли передача вообще.
	ErrTransferGone = errors.New("код передачи не действует")

	// ErrTooManyAttempts — три неверные фразы; код сожжён.
	ErrTooManyAttempts = errors.New("код передачи израсходован попытками")
)

// TransferAttempts — сколько неверных фраз гасят код.
const TransferAttempts = 3

// StartTransfer заводит передачу и возвращает её идентификатор.
//
// Живая передача на аккаунт одна: две одновременные означали бы, что аккаунт обещан
// двоим, и получит его тот, кто быстрее. Повторный вызов гасит прежнюю — владелец
// передумал, кому передавать, и это его право.
func (s *Store) StartTransfer(
	ctx context.Context, ownerUserID, virtualUserID string, codeHash []byte, ttl time.Duration,
) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var virtualPerson, ownerOf string
	err = tx.QueryRow(ctx, `
		SELECT p.person_id, COALESCE(p.owner_person_id::text, '')
		FROM users u JOIN persons p ON p.person_id = u.person_id
		WHERE u.user_id = $1 AND p.state <> 'archived'`, virtualUserID).Scan(&virtualPerson, &ownerOf)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return "", ErrUserUnknown
	} else if err != nil {
		return "", err
	}

	var ownerPerson string
	if err := tx.QueryRow(ctx,
		`SELECT person_id FROM users WHERE user_id = $1`, ownerUserID).Scan(&ownerPerson); err != nil {
		return "", ErrUserUnknown
	}
	if ownerOf == "" || ownerOf != ownerPerson {
		return "", ErrNotYourVirtual
	}

	if _, err := tx.Exec(ctx,
		`UPDATE account_transfers SET closed_at = now()
		  WHERE person_id = $1 AND closed_at IS NULL`, virtualPerson); err != nil {
		return "", err
	}
	var id string
	if err := tx.QueryRow(ctx, `
		INSERT INTO account_transfers (person_id, from_person, code_hash, expires_at)
		VALUES ($1, $2, $3, now() + $4::interval)
		RETURNING transfer_id`,
		virtualPerson, ownerPerson, codeHash, ttl.String()).Scan(&id); err != nil {
		return "", err
	}
	return id, tx.Commit(ctx)
}

// CancelTransfer гасит живую передачу этого аккаунта.
func (s *Store) CancelTransfer(ctx context.Context, ownerUserID, virtualUserID string) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE account_transfers SET closed_at = now()
		 WHERE closed_at IS NULL
		   AND person_id = (SELECT person_id FROM users WHERE user_id = $1)
		   AND from_person = (SELECT person_id FROM users WHERE user_id = $2)`,
		virtualUserID, ownerUserID)
	if isBadUUID(err) {
		return nil
	}
	return err
}

// Transfer — живая передача по хэшу кода: кого передают и чей это ключ личности.
type Transfer struct {
	ID          string
	PersonID    string
	UserID      string
	IdentityPub []byte
	Attempts    int
}

// FindTransfer ищет живую передачу по хэшу кода.
//
// Просроченная не отдаётся: срок считает **сервер**, а не устройство. Часы телефона
// переводятся в две секунды, и проверка на клиенте означала бы «код живёт столько,
// сколько я захочу».
func (s *Store) FindTransfer(ctx context.Context, codeHash []byte) (Transfer, error) {
	var t Transfer
	err := s.pool.QueryRow(ctx, `
		SELECT t.transfer_id, t.person_id, u.user_id, u.identity_pub, t.attempts
		  FROM account_transfers t
		  JOIN users u ON u.person_id = t.person_id AND u.valid_to IS NULL
		 WHERE t.code_hash = $1 AND t.closed_at IS NULL AND t.expires_at > now()`,
		codeHash).Scan(&t.ID, &t.PersonID, &t.UserID, &t.IdentityPub, &t.Attempts)
	if errors.Is(err, pgx.ErrNoRows) {
		return t, ErrTransferGone
	}
	return t, err
}

// FailedAttempt — неверная фраза. Третья гасит код.
func (s *Store) FailedAttempt(ctx context.Context, transferID string) error {
	var attempts int
	if err := s.pool.QueryRow(ctx, `
		UPDATE account_transfers SET attempts = attempts + 1
		 WHERE transfer_id = $1 RETURNING attempts`, transferID).Scan(&attempts); err != nil {
		return err
	}
	if attempts >= TransferAttempts {
		if _, err := s.pool.Exec(ctx,
			`UPDATE account_transfers SET closed_at = now() WHERE transfer_id = $1`, transferID); err != nil {
			return err
		}
		return ErrTooManyAttempts
	}
	return nil
}

// CompleteTransfer — четыре обязательных шага одной транзакцией.
//
//  1. отозвать ВСЕ устройства аккаунта: прежний владелец держал их, и пока они живы, он
//     читает всё новое;
//  2. перевязать владельца;
//  3. закрыть передачу.
//
// Четвёртый шаг — гашение бэкапа ключей под фразу — не делается: такого бэкапа в
// системе пока нет. Место для него отмечено внутри.
//
// Ротация групповых ключей здесь не делается и сделана быть не может: ключи выпускают
// участники, а не сервер. Её обязан выполнить клиент нового владельца — и это ровно тот
// случай, где сервер напоминает, но не заставляет (ADR-0017).
func (s *Store) CompleteTransfer(ctx context.Context, transferID, newOwnerUserID string) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	var personID string
	if err := tx.QueryRow(ctx, `
		SELECT person_id FROM account_transfers
		 WHERE transfer_id = $1 AND closed_at IS NULL AND expires_at > now()`,
		transferID).Scan(&personID); err != nil {
		return ErrTransferGone
	}

	var newOwnerPerson string
	if err := tx.QueryRow(ctx,
		`SELECT person_id FROM users WHERE user_id = $1`, newOwnerUserID).Scan(&newOwnerPerson); err != nil {
		return ErrUserUnknown
	}

	if _, err := tx.Exec(ctx, `
		UPDATE devices SET revoked_at = now()
		 WHERE revoked_at IS NULL AND user_id IN (SELECT user_id FROM users WHERE person_id = $1)`,
		personID); err != nil {
		return err
	}
	// ЗДЕСЬ ЖЕ ГАСИТСЯ БЭКАП КЛЮЧЕЙ ПОД ФРАЗУ — когда он появится.
	//
	// Сейчас гасить нечего: необязательного бэкапа ключей (ADR-0010, «опциональная
	// страховка») в системе нет ни в каком виде. Строки-заглушки тут нет намеренно —
	// удалять из несуществующей таблицы значило бы делать вид, что дыра закрыта. Она
	// названа в ПЛАН-КОНТАКТОВ.md: включённый бэкап открыл бы новому владельцу всю
	// историю без согласия собеседников, и передача обязана его гасить.
	if _, err := tx.Exec(ctx,
		`UPDATE persons SET owner_person_id = $2 WHERE person_id = $1`, personID, newOwnerPerson); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx,
		`UPDATE account_transfers SET closed_at = now() WHERE transfer_id = $1`, transferID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
