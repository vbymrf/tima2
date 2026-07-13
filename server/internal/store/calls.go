package store

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
)

var ErrCallNotFound = errors.New("звонок не найден")

type Call struct {
	CallID      string
	Room        string
	Kind        string
	InitiatorID string
	PeerID      string
	State       string
}

// CreateCall — новый звонок 1:1 в состоянии ringing.
func (s *Store) CreateCall(ctx context.Context, c Call) (string, error) {
	var id string
	err := s.pool.QueryRow(ctx, `
		INSERT INTO calls (room, kind, initiator_id, peer_id)
		VALUES ($1,$2,$3,$4) RETURNING call_id`,
		c.Room, c.Kind, c.InitiatorID, c.PeerID).Scan(&id)
	return id, err
}

func (s *Store) GetCall(ctx context.Context, callID string) (Call, error) {
	var c Call
	err := s.pool.QueryRow(ctx, `
		SELECT call_id, room, kind, initiator_id, peer_id, state FROM calls WHERE call_id = $1`, callID).
		Scan(&c.CallID, &c.Room, &c.Kind, &c.InitiatorID, &c.PeerID, &c.State)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return c, ErrCallNotFound
	}
	return c, err
}

// SetCallState переводит звонок в новое состояние (answered/ended/missed) с отметкой времени.
func (s *Store) SetCallState(ctx context.Context, callID, state string) error {
	col := ""
	switch state {
	case "answered":
		col = ", answered_at = now()"
	case "ended", "missed":
		col = ", ended_at = now()"
	}
	_, err := s.pool.Exec(ctx, `UPDATE calls SET state = $2`+col+` WHERE call_id = $1`, callID, state)
	return err
}

// ── Аудио-чаты (постоянные голосовые комнаты) ──

var ErrVoiceRoomNotFound = errors.New("аудио-чат не найден")

type VoiceRoom struct {
	RoomID  string
	Title   string
	OwnerID string
}

func (s *Store) CreateVoiceRoom(ctx context.Context, title, ownerID string) (string, error) {
	var id string
	err := s.pool.QueryRow(ctx, `
		INSERT INTO voice_rooms (title, owner_id) VALUES ($1,$2) RETURNING room_id`, title, ownerID).Scan(&id)
	return id, err
}

func (s *Store) GetVoiceRoom(ctx context.Context, roomID string) (VoiceRoom, error) {
	var v VoiceRoom
	err := s.pool.QueryRow(ctx, `
		SELECT room_id, title, owner_id FROM voice_rooms WHERE room_id = $1 AND closed_at IS NULL`, roomID).
		Scan(&v.RoomID, &v.Title, &v.OwnerID)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return v, ErrVoiceRoomNotFound
	}
	return v, err
}

// ListVoiceRooms — открытые аудио-чаты (новые → старые).
func (s *Store) ListVoiceRooms(ctx context.Context, limit int) ([]VoiceRoom, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	rows, err := s.pool.Query(ctx, `
		SELECT room_id, title, owner_id FROM voice_rooms
		WHERE closed_at IS NULL ORDER BY created_at DESC LIMIT $1`, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []VoiceRoom
	for rows.Next() {
		var v VoiceRoom
		if err := rows.Scan(&v.RoomID, &v.Title, &v.OwnerID); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}
