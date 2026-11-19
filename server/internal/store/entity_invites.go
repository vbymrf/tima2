// Секретные сущности и приглашения (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2).
//
// Секретная сущность не находится нигде: ни `discover`, ни карточкой, ни страницей сообщества у постороннего.
// Войти в неё можно только по приглашению — поимённому, с ответом «принять / отказаться». Показ приглашения
// человеку — карточкой в личной переписке с пригласившим (её шлёт клиент); здесь — только состояние, по которому
// сервер пускает.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

// isForeignKey — ссылка на несуществующее (23503): приглашённого нет на сервере.
func isForeignKey(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23503"
}

var (
	// ErrInviteNotFound — приглашения нет, оно чужое или на него уже ответили.
	ErrInviteNotFound = errors.New("приглашение не найдено")
	// ErrInviteExpired — срок приглашения прошёл.
	ErrInviteExpired = errors.New("срок приглашения прошёл")
)

// Invite — приглашение в группу, канал или сообщество.
type Invite struct {
	InviteID   string
	EntityKind string // group | channel | community
	EntityID   string
	InviterID  string
	InviteeID  string
	State      string // pending | accepted | declined
	CreatedAt  time.Time
	ExpiresAt  time.Time
	// Title — название сущности сейчас: приглашённый ещё не видит её и иначе узнать не может.
	Title string
	// Hidden — сущность секретная; Personal — личная группа (вступление со сменой ключа).
	Hidden   bool
	Personal bool
}

// GroupOpenToOutsider — видит ли не-участник группу (название, уровень 0, заявку).
//
// Публичная — да; личная — если ему открыта карточка (ADR-0018); **секретная — только по живому
// приглашению** (СЦ2): ни публичность, ни прежняя аудитория карточки её не открывают. Одним запросом, а не
// цепочкой проверок: признак мог смениться между ними.
func (s *Store) GroupOpenToOutsider(ctx context.Context, groupID, userID string) (bool, error) {
	var open bool
	err := s.pool.QueryRow(ctx, `
		SELECT CASE
		         WHEN g.hidden THEN EXISTS (
		              SELECT 1 FROM entity_invites i
		               WHERE i.entity_kind = 'group' AND i.entity_id = g.group_id AND i.invitee_id = $2
		                 AND i.state = 'pending' AND i.expires_at > now())
		         WHEN g.kind = 'public' THEN TRUE
		         ELSE EXISTS (SELECT 1 FROM group_card_audience a WHERE a.group_id = g.group_id AND a.user_id = $2)
		       END
		  FROM groups g WHERE g.group_id = $1 AND g.deleted_at IS NULL`, groupID, userID).Scan(&open)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return false, nil
	}
	return open, err
}

// SetChannelHidden — сделать канал секретным или снять признак (владелец, проверяет выдача).
func (s *Store) SetChannelHidden(ctx context.Context, channelID string, hidden bool) error {
	ct, err := s.pool.Exec(ctx, `UPDATE channels SET hidden = $2 WHERE channel_id = $1 AND deleted_at IS NULL`, channelID, hidden)
	if isBadUUID(err) || (err == nil && ct.RowsAffected() == 0) {
		return ErrChannelNotFound
	}
	return err
}

// SetCommunityHidden — сделать сообщество секретным или снять признак.
func (s *Store) SetCommunityHidden(ctx context.Context, communityID string, hidden bool) error {
	ct, err := s.pool.Exec(ctx, `UPDATE communities SET hidden = $2 WHERE community_id = $1 AND deleted_at IS NULL`, communityID, hidden)
	if isBadUUID(err) || (err == nil && ct.RowsAffected() == 0) {
		return ErrCommunityNotFound
	}
	return err
}

// HasPendingInvite — есть ли у человека живое приглашение в сущность.
func (s *Store) HasPendingInvite(ctx context.Context, kind, entityID, userID string) (bool, error) {
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS (SELECT 1 FROM entity_invites
		                WHERE entity_kind = $1 AND entity_id = $2 AND invitee_id = $3
		                  AND state = 'pending' AND expires_at > now())`, kind, entityID, userID).Scan(&ok)
	if isBadUUID(err) {
		return false, nil
	}
	return ok, err
}

// CreateInvite — пригласить: новое приглашение или прежнее (отклонённое, истёкшее, принятое и ушедший) —
// снова живое, с новым сроком и новым пригласившим. Одно на человека и сущность.
func (s *Store) CreateInvite(ctx context.Context, kind, entityID, inviterID, inviteeID string, ttl time.Duration) (Invite, error) {
	inv := Invite{EntityKind: kind, EntityID: entityID, InviterID: inviterID, InviteeID: inviteeID, State: "pending"}
	err := s.pool.QueryRow(ctx, `
		INSERT INTO entity_invites (entity_kind, entity_id, inviter_id, invitee_id, expires_at)
		VALUES ($1, $2, $3, $4, now() + make_interval(secs => $5))
		ON CONFLICT (entity_kind, entity_id, invitee_id) DO UPDATE
		   SET inviter_id = EXCLUDED.inviter_id, state = 'pending', created_at = now(),
		       expires_at = EXCLUDED.expires_at, answered_at = NULL
		RETURNING invite_id, created_at, expires_at`,
		kind, entityID, inviterID, inviteeID, ttl.Seconds()).Scan(&inv.InviteID, &inv.CreatedAt, &inv.ExpiresAt)
	if isBadUUID(err) || isForeignKey(err) {
		return inv, ErrUserUnknown
	}
	return inv, err
}

// inviteSelect — приглашение с названием сущности; сущность удалена — название пустое.
const inviteSelect = `
	SELECT i.invite_id, i.entity_kind, i.entity_id::text, i.inviter_id::text, i.invitee_id::text, i.state,
	       i.created_at, i.expires_at,
	       COALESCE(g.title, c.title, cm.title, ''),
	       COALESCE(g.hidden, c.hidden, cm.hidden, FALSE),
	       COALESCE(g.kind = 'private', FALSE)
	  FROM entity_invites i
	  LEFT JOIN groups g       ON i.entity_kind = 'group'     AND g.group_id = i.entity_id      AND g.deleted_at IS NULL
	  LEFT JOIN channels c     ON i.entity_kind = 'channel'   AND c.channel_id = i.entity_id    AND c.deleted_at IS NULL
	  LEFT JOIN communities cm ON i.entity_kind = 'community' AND cm.community_id = i.entity_id AND cm.deleted_at IS NULL`

func scanInvite(row pgx.Row) (Invite, error) {
	var v Invite
	err := row.Scan(&v.InviteID, &v.EntityKind, &v.EntityID, &v.InviterID, &v.InviteeID, &v.State,
		&v.CreatedAt, &v.ExpiresAt, &v.Title, &v.Hidden, &v.Personal)
	return v, err
}

// MyInvites — живые приглашения человеку: ещё без ответа, срок не вышел, сущность есть.
func (s *Store) MyInvites(ctx context.Context, userID string) ([]Invite, error) {
	rows, err := s.pool.Query(ctx, inviteSelect+`
		 WHERE i.invitee_id = $1 AND i.state = 'pending' AND i.expires_at > now()
		   AND COALESCE(g.group_id, c.channel_id, cm.community_id) IS NOT NULL
		 ORDER BY i.created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Invite
	for rows.Next() {
		v, err := scanInvite(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// GetInvite — приглашение по номеру.
func (s *Store) GetInvite(ctx context.Context, inviteID string) (Invite, error) {
	v, err := scanInvite(s.pool.QueryRow(ctx, inviteSelect+` WHERE i.invite_id = $1`, inviteID))
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return v, ErrInviteNotFound
	}
	return v, err
}

// AnswerInvite — ответ приглашённого. Только своё, только живое; ответ — один.
func (s *Store) AnswerInvite(ctx context.Context, inviteID, inviteeID string, accept bool) (Invite, error) {
	v, err := s.GetInvite(ctx, inviteID)
	if err != nil {
		return v, err
	}
	if v.InviteeID != inviteeID || v.State != "pending" {
		return v, ErrInviteNotFound
	}
	if !v.ExpiresAt.After(time.Now()) {
		return v, ErrInviteExpired
	}
	state := "declined"
	if accept {
		state = "accepted"
	}
	ct, err := s.pool.Exec(ctx, `
		UPDATE entity_invites SET state = $2, answered_at = now()
		 WHERE invite_id = $1 AND state = 'pending'`, inviteID, state)
	if err != nil {
		return v, err
	}
	if ct.RowsAffected() == 0 {
		return v, ErrInviteNotFound
	}
	v.State = state
	return v, nil
}
