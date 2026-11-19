// Звуковой чат в сообществе (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ7). Отдельным типом: у `*Store` бюджет методов.
package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

var (
	// ErrRoomFull — предел участников.
	ErrRoomFull = errors.New("звуковой чат полон")
	// ErrKicked — выгнан до конца разговора.
	ErrKicked = errors.New("выгнан из звукового чата")
)

// CommunityVoice — звуковые чаты сообществ.
type CommunityVoice struct{ s *Store }

// CommunityVoiceOf — звуковые чаты поверх хранилища.
func CommunityVoiceOf(s *Store) CommunityVoice { return CommunityVoice{s: s} }

// VoiceChat — звуковой чат сообщества с настройками.
type VoiceChat struct {
	RoomID          string
	Title           string
	OwnerID         string
	CommunityID     string
	GroupID         string
	AllowMute       bool
	HostAllowed     bool
	HostCanKick     bool
	MaxParticipants int
	HostID          string
	Present         []string
}

// Create — новый звуковой чат в сообществе; [groupID] — его текст (может быть пуст).
func (v CommunityVoice) Create(ctx context.Context, communityID, title, ownerID, groupID string) (string, error) {
	var id string
	var group any
	if groupID != "" {
		group = groupID
	}
	err := v.s.pool.QueryRow(ctx, `
		INSERT INTO voice_rooms (title, owner_id, community_id, group_id) VALUES ($1, $2, $3, $4) RETURNING room_id::text`,
		title, ownerID, communityID, group).Scan(&id)
	if isBadUUID(err) || isForeignKey(err) {
		return "", ErrCommunityNotFound
	}
	return id, err
}

const voiceSelect = `
	SELECT r.room_id::text, r.title, r.owner_id::text, COALESCE(r.community_id::text, ''), COALESCE(r.group_id::text, ''),
	       r.allow_mute, r.host_allowed, r.host_can_kick, r.max_participants, COALESCE(r.host_id::text, ''),
	       COALESCE((SELECT array_agg(p.user_id::text ORDER BY p.joined_at) FROM voice_room_present p WHERE p.room_id = r.room_id), '{}')
	  FROM voice_rooms r`

func scanVoice(row pgx.Row) (VoiceChat, error) {
	var c VoiceChat
	err := row.Scan(&c.RoomID, &c.Title, &c.OwnerID, &c.CommunityID, &c.GroupID, &c.AllowMute, &c.HostAllowed,
		&c.HostCanKick, &c.MaxParticipants, &c.HostID, &c.Present)
	return c, err
}

// Get — звуковой чат по номеру (только привязанный к сообществу и открытый).
func (v CommunityVoice) Get(ctx context.Context, roomID string) (VoiceChat, error) {
	c, err := scanVoice(v.s.pool.QueryRow(ctx, voiceSelect+` WHERE r.room_id = $1 AND r.closed_at IS NULL AND r.community_id IS NOT NULL`, roomID))
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return c, ErrVoiceRoomNotFound
	}
	return c, err
}

// List — звуковые чаты сообщества.
func (v CommunityVoice) List(ctx context.Context, communityID string) ([]VoiceChat, error) {
	rows, err := v.s.pool.Query(ctx, voiceSelect+` WHERE r.community_id = $1 AND r.closed_at IS NULL ORDER BY r.created_at`, communityID)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []VoiceChat
	for rows.Next() {
		c, err := scanVoice(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// Enter — вошёл: выгнанный не входит, сверх предела — нет. Повторный вход того же — не считается вторым.
func (v CommunityVoice) Enter(ctx context.Context, roomID, userID string) error {
	tx, err := v.s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck
	var max int
	if err := tx.QueryRow(ctx, `SELECT max_participants FROM voice_rooms WHERE room_id = $1 FOR UPDATE`, roomID).Scan(&max); err != nil {
		return err
	}
	var kicked bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM voice_room_kicked WHERE room_id = $1 AND user_id = $2)`, roomID, userID).Scan(&kicked); err != nil {
		return err
	}
	if kicked {
		return ErrKicked
	}
	var present int
	var already bool
	if err := tx.QueryRow(ctx, `
		SELECT count(*), COALESCE(bool_or(user_id = $2), FALSE) FROM voice_room_present WHERE room_id = $1`, roomID, userID).Scan(&present, &already); err != nil {
		return err
	}
	if !already && present >= max {
		return ErrRoomFull
	}
	if _, err := tx.Exec(ctx, `INSERT INTO voice_room_present (room_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, roomID, userID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// Leave — вышел; ведущий, ушедший из чата, место освобождает. Пустая комната — выгнанные прощены (разговор кончился).
func (v CommunityVoice) Leave(ctx context.Context, roomID, userID string) error {
	if _, err := v.s.pool.Exec(ctx, `DELETE FROM voice_room_present WHERE room_id = $1 AND user_id = $2`, roomID, userID); err != nil {
		return err
	}
	if _, err := v.s.pool.Exec(ctx, `UPDATE voice_rooms SET host_id = NULL WHERE room_id = $1 AND host_id = $2`, roomID, userID); err != nil {
		return err
	}
	_, err := v.s.pool.Exec(ctx, `
		DELETE FROM voice_room_kicked k WHERE k.room_id = $1
		   AND NOT EXISTS (SELECT 1 FROM voice_room_present p WHERE p.room_id = $1)`, roomID)
	return err
}

// Kick — выгнать: из вошедших и не пускать до конца разговора.
func (v CommunityVoice) Kick(ctx context.Context, roomID, userID string) error {
	if _, err := v.s.pool.Exec(ctx, `INSERT INTO voice_room_kicked (room_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`, roomID, userID); err != nil {
		return err
	}
	if _, err := v.s.pool.Exec(ctx, `UPDATE voice_rooms SET host_id = NULL WHERE room_id = $1 AND host_id = $2`, roomID, userID); err != nil {
		return err
	}
	_, err := v.s.pool.Exec(ctx, `DELETE FROM voice_room_present WHERE room_id = $1 AND user_id = $2`, roomID, userID)
	return err
}

// SetHost — ведущий; пусто — место свободно.
func (v CommunityVoice) SetHost(ctx context.Context, roomID, userID string) error {
	var host any
	if userID != "" {
		host = userID
	}
	_, err := v.s.pool.Exec(ctx, `UPDATE voice_rooms SET host_id = $2 WHERE room_id = $1`, roomID, host)
	return err
}

// Settings — настройки чата.
func (v CommunityVoice) Settings(ctx context.Context, roomID string, allowMute, hostAllowed, hostCanKick bool, max int) error {
	_, err := v.s.pool.Exec(ctx, `
		UPDATE voice_rooms SET allow_mute = $2, host_allowed = $3, host_can_kick = $4, max_participants = $5,
		       host_id = CASE WHEN $3 THEN host_id ELSE NULL END
		 WHERE room_id = $1`, roomID, allowMute, hostAllowed, hostCanKick, max)
	return err
}

// CloseFor — закрыть звуковые чаты сообщества (сообщество удалено).
func (v CommunityVoice) CloseFor(ctx context.Context, communityID string) error {
	_, err := v.s.pool.Exec(ctx, `UPDATE voice_rooms SET closed_at = now() WHERE community_id = $1 AND closed_at IS NULL`, communityID)
	return err
}
