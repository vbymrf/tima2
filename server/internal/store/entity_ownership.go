// Владение сущностью по ключу личности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6, СЦ11).
//
// Цепочка `entity_ownership` — истина; `owner_id` — быстрый указатель, меняется той же транзакцией, что и новое
// звено. Отдельным типом, а не методами `*Store`: у хранилища бюджет методов (architecture_test), и владение —
// своя узкая забота.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

var (
	// ErrOfferNotFound — предложения нет, оно чужое или погашено.
	ErrOfferNotFound = errors.New("предложения передачи нет")
	// ErrOfferExpired — срок предложения прошёл.
	ErrOfferExpired = errors.New("срок предложения прошёл")
	// ErrChainMoved — цепочка ушла вперёд: номер звена в акте уже занят.
	ErrChainMoved = errors.New("цепочка владения изменилась")
)

// Ownership — цепочка владения и предложения передачи.
type Ownership struct{ s *Store }

// OwnershipOf — владение поверх хранилища.
func OwnershipOf(s *Store) Ownership { return Ownership{s: s} }

// OwnershipLink — звено цепочки.
type OwnershipLink struct {
	LinkNo      int
	OwnerID     string
	IdentityPub []byte
	Kind        string // genesis | transfer | rebind
	CreatedAt   time.Time
}

// TransferOffer — живое предложение передачи.
type TransferOffer struct {
	EntityKind  string
	EntityID    string
	LinkNo      int
	FromID      string
	ToID        string
	ExpiresUnix int64
	OwnerSig    []byte
	Attempts    int
	Title       string
}

// Chain — цепочка сущности от создания.
func (o Ownership) Chain(ctx context.Context, kind, entityID string) ([]OwnershipLink, error) {
	rows, err := o.s.pool.Query(ctx, `
		SELECT link_no, owner_id::text, identity_pub, link_kind, created_at
		  FROM entity_ownership WHERE entity_kind = $1 AND entity_id = $2 ORDER BY link_no`, kind, entityID)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []OwnershipLink
	for rows.Next() {
		var l OwnershipLink
		if err := rows.Scan(&l.LinkNo, &l.OwnerID, &l.IdentityPub, &l.Kind, &l.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, l)
	}
	return out, rows.Err()
}

// Last — последнее звено; нет цепочки — `ok = false`.
func (o Ownership) Last(ctx context.Context, kind, entityID string) (OwnershipLink, bool, error) {
	var l OwnershipLink
	err := o.s.pool.QueryRow(ctx, `
		SELECT link_no, owner_id::text, identity_pub, link_kind, created_at
		  FROM entity_ownership WHERE entity_kind = $1 AND entity_id = $2 ORDER BY link_no DESC LIMIT 1`,
		kind, entityID).Scan(&l.LinkNo, &l.OwnerID, &l.IdentityPub, &l.Kind, &l.CreatedAt)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return l, false, nil
	}
	return l, err == nil, err
}

// IdentityPub — текущий ключ личности аккаунта.
func (o Ownership) IdentityPub(ctx context.Context, userID string) ([]byte, error) {
	var pub []byte
	err := o.s.pool.QueryRow(ctx, `SELECT identity_pub FROM users WHERE user_id = $1`, userID).Scan(&pub)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return nil, ErrUserUnknown
	}
	return pub, err
}

// Offer — поставить предложение (заменяет прежнее живое у этой сущности).
func (o Ownership) Offer(ctx context.Context, f TransferOffer) error {
	_, err := o.s.pool.Exec(ctx, `
		INSERT INTO entity_transfer_offers (entity_kind, entity_id, link_no, from_id, to_id, expires_unix, owner_sig)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (entity_kind, entity_id) DO UPDATE
		   SET link_no = EXCLUDED.link_no, from_id = EXCLUDED.from_id, to_id = EXCLUDED.to_id,
		       expires_unix = EXCLUDED.expires_unix, owner_sig = EXCLUDED.owner_sig, attempts = 0, created_at = now()`,
		f.EntityKind, f.EntityID, f.LinkNo, f.FromID, f.ToID, f.ExpiresUnix, f.OwnerSig)
	if isForeignKey(err) || isBadUUID(err) {
		return ErrUserUnknown
	}
	return err
}

const offerSelect = `
	SELECT o.entity_kind, o.entity_id::text, o.link_no, o.from_id::text, o.to_id::text, o.expires_unix, o.owner_sig,
	       o.attempts, COALESCE(g.title, c.title, cm.title, '')
	  FROM entity_transfer_offers o
	  LEFT JOIN groups g       ON o.entity_kind = 'group'     AND g.group_id = o.entity_id      AND g.deleted_at IS NULL
	  LEFT JOIN channels c     ON o.entity_kind = 'channel'   AND c.channel_id = o.entity_id    AND c.deleted_at IS NULL
	  LEFT JOIN communities cm ON o.entity_kind = 'community' AND cm.community_id = o.entity_id AND cm.deleted_at IS NULL`

func scanOffer(row pgx.Row) (TransferOffer, error) {
	var f TransferOffer
	err := row.Scan(&f.EntityKind, &f.EntityID, &f.LinkNo, &f.FromID, &f.ToID, &f.ExpiresUnix, &f.OwnerSig, &f.Attempts, &f.Title)
	return f, err
}

// GetOffer — живое предложение сущности.
func (o Ownership) GetOffer(ctx context.Context, kind, entityID string) (TransferOffer, error) {
	f, err := scanOffer(o.s.pool.QueryRow(ctx, offerSelect+` WHERE o.entity_kind = $1 AND o.entity_id = $2`, kind, entityID))
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return f, ErrOfferNotFound
	}
	return f, err
}

// OffersTo — предложения человеку, ещё не просроченные.
func (o Ownership) OffersTo(ctx context.Context, userID string, now time.Time) ([]TransferOffer, error) {
	rows, err := o.s.pool.Query(ctx, offerSelect+` WHERE o.to_id = $1 AND o.expires_unix > $2 ORDER BY o.created_at DESC`,
		userID, now.Unix())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []TransferOffer
	for rows.Next() {
		f, err := scanOffer(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, f)
	}
	return out, rows.Err()
}

// FailedAttempt — неверная фраза принимающего; третья гасит предложение. `true` — погашено.
func (o Ownership) FailedAttempt(ctx context.Context, kind, entityID string) (bool, error) {
	var attempts int
	err := o.s.pool.QueryRow(ctx, `
		UPDATE entity_transfer_offers SET attempts = attempts + 1
		 WHERE entity_kind = $1 AND entity_id = $2 RETURNING attempts`, kind, entityID).Scan(&attempts)
	if errors.Is(err, pgx.ErrNoRows) {
		return true, nil
	}
	if err != nil {
		return false, err
	}
	if attempts >= 3 {
		return true, o.DropOffer(ctx, kind, entityID)
	}
	return false, nil
}

// DropOffer — погасить предложение (отказ, отзыв, три ошибки).
func (o Ownership) DropOffer(ctx context.Context, kind, entityID string) error {
	_, err := o.s.pool.Exec(ctx, `DELETE FROM entity_transfer_offers WHERE entity_kind = $1 AND entity_id = $2`, kind, entityID)
	return err
}

// Complete — передача одной транзакцией: звено, `owner_id`, роли; предложение гаснет.
//
// Номер звена проверяется вставкой с первичным ключом: повтор того же акта или гонка двух передач упрётся в
// занятый номер (ErrChainMoved). Роли: в группе прежний владелец — участник, принимающий — владелец; у канала и
// сообщества роль — `owner_id`, прежний остаётся подписчиком.
func (o Ownership) Complete(ctx context.Context, f TransferOffer, toIdentityPub, newSig []byte) error {
	tx, err := o.s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck
	var last int
	if err := tx.QueryRow(ctx, `
		SELECT COALESCE(max(link_no), 0) FROM entity_ownership WHERE entity_kind = $1 AND entity_id = $2`,
		f.EntityKind, f.EntityID).Scan(&last); err != nil {
		return err
	}
	if last+1 != f.LinkNo {
		return ErrChainMoved
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO entity_ownership (entity_kind, entity_id, link_no, owner_id, identity_pub, link_kind, prev_sig, new_sig, expires_unix)
		VALUES ($1, $2, $3, $4, $5, 'transfer', $6, $7, $8)`,
		f.EntityKind, f.EntityID, f.LinkNo, f.ToID, toIdentityPub, f.OwnerSig, newSig, f.ExpiresUnix); err != nil {
		return err
	}
	switch f.EntityKind {
	case "group":
		if _, err := tx.Exec(ctx, `UPDATE groups SET owner_id = $2 WHERE group_id = $1`, f.EntityID, f.ToID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `
			UPDATE memberships SET role = 'member'
			 WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`, f.EntityID, f.FromID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `
			UPDATE memberships SET role = 'owner'
			 WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`, f.EntityID, f.ToID); err != nil {
			return err
		}
	case "channel":
		if _, err := tx.Exec(ctx, `UPDATE channels SET owner_id = $2 WHERE channel_id = $1`, f.EntityID, f.ToID); err != nil {
			return err
		}
		// Принимающий был модератором — теперь владелец; модератором ему быть незачем.
		if _, err := tx.Exec(ctx, `DELETE FROM channel_moderators WHERE channel_id = $1 AND user_id = $2`, f.EntityID, f.ToID); err != nil {
			return err
		}
	case "community":
		if _, err := tx.Exec(ctx, `UPDATE communities SET owner_id = $2 WHERE community_id = $1`, f.EntityID, f.ToID); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `DELETE FROM community_admins WHERE community_id = $1 AND user_id = $2`, f.EntityID, f.ToID); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(ctx, `DELETE FROM entity_transfer_offers WHERE entity_kind = $1 AND entity_id = $2`, f.EntityKind, f.EntityID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
