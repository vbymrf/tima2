package store

// Групповые звонки (миграция 0023). Здесь только сигналинг и состояние: медиа идёт
// через LiveKit, и качеством занимается он (ADR-0006 Поправка-1).

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ParticipantState — что происходит с приглашённым.
type ParticipantState string

const (
	// PartInvited — приглашён, ещё не входил.
	PartInvited ParticipantState = "invited"
	// PartJoined — сейчас в комнате (по вебхуку LiveKit).
	PartJoined ParticipantState = "joined"
	// PartLeft — вышел или выпал. Может войти снова, пока звонок активен.
	PartLeft ParticipantState = "left"
)

var ErrNotInvited = errors.New("пользователь не приглашён в этот звонок")

// CreateGroupCall заводит групповой звонок и приглашения участникам.
// Инициатор попадает в список наравне с остальными: в группе он не медиа-хаб и
// ничем не отличается от других — его обрыв не роняет звонок.
func (s *Store) CreateGroupCall(ctx context.Context, room, kind, groupID, initiatorID string, members []string) (string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return "", err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op

	var callID string
	if err := tx.QueryRow(ctx, `
		INSERT INTO calls (room, kind, type, group_id, initiator_id, state)
		VALUES ($1, $2, 'group', $3, $4, 'ringing')
		RETURNING call_id`, room, kind, groupID, initiatorID).Scan(&callID); err != nil {
		return "", err
	}
	seen := map[string]bool{}
	for _, m := range append([]string{initiatorID}, members...) {
		if seen[m] {
			continue
		}
		seen[m] = true
		if _, err := tx.Exec(ctx, `
			INSERT INTO call_participants (call_id, user_id) VALUES ($1, $2)
			ON CONFLICT DO NOTHING`, callID, m); err != nil {
			return "", err
		}
	}
	return callID, tx.Commit(ctx)
}

// CallForJoin — данные, нужные для решения «пускать ли».
type CallForJoin struct {
	Room  string
	Kind  string
	Type  string
	State string
}

// CallForJoinByID возвращает звонок и проверяет, что пользователь в него приглашён.
//
// Право на вход есть только у приглашённого и только пока звонок активен. Это
// единственное место, где решается, кому выдать токен: LiveKit пустит любого, у
// кого токен есть.
func (s *Store) CallForJoinByID(ctx context.Context, callID, userID string) (CallForJoin, error) {
	var c CallForJoin
	err := s.pool.QueryRow(ctx, `
		SELECT c.room, c.kind, c.type, c.state
		FROM calls c
		JOIN call_participants p ON p.call_id = c.call_id AND p.user_id = $2
		WHERE c.call_id = $1`, callID, userID).Scan(&c.Room, &c.Kind, &c.Type, &c.State)
	if errors.Is(err, pgx.ErrNoRows) {
		return CallForJoin{}, ErrNotInvited
	}
	return c, err
}

// SetParticipantState отмечает вход или выход участника. Источник правды —
// вебхуки LiveKit: собственного учёта «жив или нет» бэкенд не ведёт и таймеры SFU
// не дублирует.
func (s *Store) SetParticipantState(ctx context.Context, callID, userID string, state ParticipantState, at time.Time) error {
	var col string
	switch state {
	case PartJoined:
		col = "joined_at"
	case PartLeft:
		col = "left_at"
	default:
		col = ""
	}
	q := `UPDATE call_participants SET state = $3 WHERE call_id = $1 AND user_id = $2`
	if col != "" {
		q = `UPDATE call_participants SET state = $3, ` + col + ` = $4 WHERE call_id = $1 AND user_id = $2`
		_, err := s.pool.Exec(ctx, q, callID, userID, state, at)
		return err
	}
	_, err := s.pool.Exec(ctx, q, callID, userID, state)
	return err
}

// CallIDByRoom — звонок по имени комнаты LiveKit (вебхуки приходят с ним).
func (s *Store) CallIDByRoom(ctx context.Context, room string) (string, string, error) {
	var callID, callType string
	err := s.pool.QueryRow(ctx,
		`SELECT call_id, type FROM calls WHERE room = $1`, room).Scan(&callID, &callType)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", "", ErrCallNotFound
	}
	return callID, callType, err
}

// ActiveParticipants — кто сейчас в комнате.
func (s *Store) ActiveParticipants(ctx context.Context, callID string) ([]string, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT user_id FROM call_participants WHERE call_id = $1 AND state = 'joined'`, callID)
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

// CallParticipants — все приглашённые с их состояниями.
func (s *Store) CallParticipants(ctx context.Context, callID string) (map[string]ParticipantState, error) {
	rows, err := s.pool.Query(ctx,
		`SELECT user_id, state FROM call_participants WHERE call_id = $1`, callID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string]ParticipantState)
	for rows.Next() {
		var id string
		var st ParticipantState
		if err := rows.Scan(&id, &st); err != nil {
			return nil, err
		}
		out[id] = st
	}
	return out, rows.Err()
}
