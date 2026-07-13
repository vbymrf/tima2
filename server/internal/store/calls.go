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
