package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// «Начать заново» и её отмена (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ6, Р27–Р31).

// GroupClaim — заявка новой личности на место прежней в группе.
type GroupClaim struct {
	GroupID    string
	UserID     string
	FromUserID string
	Role       string
	CreatedAt  time.Time
}

// ErrNoClaim — заявки нет (подтвердили, отменили или не было).
var ErrNoClaim = errors.New("заявки новой личности нет")

// ErrNothingToCancel — у аккаунта нет личности новее этой.
var ErrNothingToCancel = errors.New("отменять нечего: эта личность и есть текущая")

// CopyGroupClaims заводит заявки новой личности во все группы, где состоит прежняя.
// Возвращает группы, куда заявки легли, — их владельцам и модераторам сообщается.
func (s *Store) CopyGroupClaims(ctx context.Context, fromUserID, toUserID string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		INSERT INTO identity_group_claims (group_id, user_id, from_user_id, role)
		SELECT target_id, $2, $1, role FROM memberships
		WHERE target_type = 'group' AND user_id = $1 AND left_at IS NULL
		ON CONFLICT (group_id, user_id) DO NOTHING
		RETURNING group_id`, fromUserID, toUserID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var g string
		if err := rows.Scan(&g); err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}

// ListGroupClaims — заявки в группу, старые первыми.
func (s *Store) ListGroupClaims(ctx context.Context, groupID string) ([]GroupClaim, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT group_id, user_id, from_user_id, role, created_at FROM identity_group_claims
		WHERE group_id = $1 ORDER BY created_at`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []GroupClaim
	for rows.Next() {
		var c GroupClaim
		if err := rows.Scan(&c.GroupID, &c.UserID, &c.FromUserID, &c.Role, &c.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// ConfirmGroupClaim — новая личность становится участником с ролью прежней, прежняя выходит.
// Возвращает прежнюю личность. Смену ключа группы просит вызывающий.
func (s *Store) ConfirmGroupClaim(ctx context.Context, groupID, userID string) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op
	var from, role string
	err = tx.QueryRow(ctx, `
		DELETE FROM identity_group_claims WHERE group_id = $1 AND user_id = $2
		RETURNING from_user_id, role`, groupID, userID).Scan(&from, &role)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", ErrNoClaim
	} else if err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE memberships SET left_at = now()
		WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`, groupID, from); err != nil {
		return "", err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO memberships (target_type, target_id, user_id, role)
		VALUES ('group', $1, $2, $3)`, groupID, userID, role); err != nil {
		return "", err
	}
	return from, tx.Commit(ctx)
}

// CancelNewerIdentities — отмена «начать заново» (Р27): все личности аккаунта новее keep
// закрываются и помечаются отменёнными, их устройства и ключи подписи устройств отзываются,
// заявки в группы снимаются; keep снова текущая. Возвращает отменённые личности.
func (s *Store) CancelNewerIdentities(ctx context.Context, personID, keepUserID string) ([]string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op
	rows, err := tx.Query(ctx, `
		SELECT u.user_id FROM users u, users k
		WHERE k.user_id = $2 AND u.person_id = $1 AND u.user_id <> $2
		  AND u.valid_from > k.valid_from AND u.cancelled_at IS NULL`, personID, keepUserID)
	if err != nil {
		return nil, err
	}
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if len(ids) == 0 {
		return nil, ErrNothingToCancel
	}
	// Порядок важен: частичный уникальный индекс «одна текущая личность на аккаунт» не даст
	// открыть keep, пока текущей остаётся отменяемая.
	for _, q := range []string{
		`UPDATE users SET valid_to = COALESCE(valid_to, now()), cancelled_at = now() WHERE user_id = ANY($1)`,
		`UPDATE devices SET revoked_at = now() WHERE user_id = ANY($1) AND revoked_at IS NULL`,
		`UPDATE account_signing_keys SET revoked_at = now() WHERE user_id = ANY($1) AND revoked_at IS NULL`,
		`DELETE FROM identity_group_claims WHERE user_id = ANY($1)`,
	} {
		if _, err := tx.Exec(ctx, q, ids); err != nil {
			return nil, err
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE users SET valid_to = NULL WHERE user_id = $1`, keepUserID); err != nil {
		return nil, err
	}
	return ids, tx.Commit(ctx)
}

// DeviceCertified — заверено ли устройство действующей цепочкой (для отмены личности, Р31).
func (s *Store) DeviceCertified(ctx context.Context, userID, deviceID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT CASE d.cert_by
		         WHEN 'identity' THEN true
		         WHEN 'ask' THEN EXISTS (SELECT 1 FROM account_signing_keys k
		                                 WHERE k.ask_id = d.cert_ask_id AND k.revoked_at IS NULL)
		         ELSE false END
		FROM devices d WHERE d.device_id = $1 AND d.user_id = $2 AND d.revoked_at IS NULL`, deviceID, userID).Scan(&ok)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	return ok, err
}
