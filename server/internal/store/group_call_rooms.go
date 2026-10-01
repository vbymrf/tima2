package store

// Групповой звонок в личной группе (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ.md, ГЗ1–ГЗ2; миграция 0058).
//
// Звонок идёт в группе: в существующей личной, а без неё — во временной, которая живёт
// до `call_ttl_until`. На группу — один идущий звонок: повторный звонок идёт в него же.

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

var (
	// ErrCallFull — в звонке уже предел участников (решение 4: 25, задаёт сервер).
	ErrCallFull = errors.New("в звонке нет мест")
	// ErrRemovedFromCall — создатель удалил человека из этого звонка (решение 17).
	ErrRemovedFromCall = errors.New("удалён из этого звонка")
)

// CallParticipant — участник группового звонка, как его видит журнал звонка.
type CallParticipant struct {
	UserID  string
	State   ParticipantState
	Invited bool // отмечен создателем; false — вошёл сам по полосе в группе
	Removed bool
	Joined  bool // хоть раз был в комнате
}

// CreateRoomCall заводит звонок в группе: создатель и отмеченные (решение 15).
// `ring` — звали ли звонком: пропущенный потом достаётся только им.
func (s *Store) CreateRoomCall(ctx context.Context, room, kind, groupID, creatorID string, invited []string, ring bool) (string, error) {
	callID, err := s.CreateGroupCall(ctx, room, kind, groupID, creatorID, invited)
	if err != nil {
		return "", err
	}
	if ring {
		if _, err := s.pool.Exec(ctx, `UPDATE calls SET ring = true WHERE call_id = $1`, callID); err != nil {
			return "", err
		}
	}
	return callID, nil
}

// CallRings — звали ли звонком.
func (s *Store) CallRings(ctx context.Context, callID string) (bool, error) {
	var ring bool
	err := s.pool.QueryRow(ctx, `SELECT ring FROM calls WHERE call_id = $1`, callID).Scan(&ring)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return false, ErrCallNotFound
	}
	return ring, err
}

// LiveGroupCall — идущий звонок группы. ErrCallNotFound — звонка нет.
func (s *Store) LiveGroupCall(ctx context.Context, groupID string) (Call, error) {
	var c Call
	var paused *time.Time
	err := s.pool.QueryRow(ctx, `
		SELECT call_id, room, kind, initiator_id, state, created_at, paused_at
		  FROM calls
		 WHERE group_id = $1 AND type = 'group' AND state IN ('ringing', 'answered')
		 ORDER BY created_at DESC
		 LIMIT 1`, groupID).
		Scan(&c.CallID, &c.Room, &c.Kind, &c.InitiatorID, &c.State, &c.CreatedAt, &paused)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return c, ErrCallNotFound
	}
	if paused != nil {
		c.PausedAt = *paused
	}
	c.Type, c.GroupID = "group", groupID
	return c, err
}

// GroupCallParticipants — все участники звонка: отмеченные, вошедшие сами, удалённые.
func (s *Store) GroupCallParticipants(ctx context.Context, callID string) ([]CallParticipant, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT user_id::text, state, invited, removed_at IS NOT NULL, joined_at IS NOT NULL
		  FROM call_participants WHERE call_id = $1
		 ORDER BY invited_at`, callID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []CallParticipant
	for rows.Next() {
		var p CallParticipant
		if err := rows.Scan(&p.UserID, &p.State, &p.Invited, &p.Removed, &p.Joined); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// JoinGroupCall — пускать ли участника группы в её звонок, и запись о нём.
//
// Право на вход — членство в группе (решение 2), его проверяет вызывающий. Здесь — то,
// что знает только звонок: не удалён ли человек из него и есть ли место. Место считается
// по тем, кто сейчас в комнате, без самого входящего: выпавший и вернувшийся места не
// занимает дважды.
func (s *Store) JoinGroupCall(ctx context.Context, callID, userID string, max int) error {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op

	var removed bool
	err = tx.QueryRow(ctx, `
		SELECT removed_at IS NOT NULL FROM call_participants WHERE call_id = $1 AND user_id = $2`,
		callID, userID).Scan(&removed)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return err
	}
	if removed {
		return ErrRemovedFromCall
	}
	if max > 0 {
		var inRoom int
		if err := tx.QueryRow(ctx, `
			SELECT count(*) FROM call_participants
			 WHERE call_id = $1 AND state = 'joined' AND user_id <> $2`, callID, userID).Scan(&inRoom); err != nil {
			return err
		}
		if inRoom >= max {
			return ErrCallFull
		}
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO call_participants (call_id, user_id, invited) VALUES ($1, $2, false)
		ON CONFLICT DO NOTHING`, callID, userID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

// RemoveFromCall — создатель удалил участника из звонка. Из группы он не удаляется.
func (s *Store) RemoveFromCall(ctx context.Context, callID, userID string) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO call_participants (call_id, user_id, state, invited, removed_at, left_at)
		VALUES ($1, $2, 'left', false, now(), now())
		ON CONFLICT (call_id, user_id)
		DO UPDATE SET removed_at = now(), state = 'left', left_at = now()`, callID, userID)
	return err
}

// SetCallPaused — пауза группового звонка (решение 5).
func (s *Store) SetCallPaused(ctx context.Context, callID string, paused bool) error {
	q := `UPDATE calls SET paused_at = NULL WHERE call_id = $1`
	if paused {
		q = `UPDATE calls SET paused_at = COALESCE(paused_at, now()) WHERE call_id = $1`
	}
	_, err := s.pool.Exec(ctx, q, callID)
	return err
}

// BumpCallGroupTTL — звонок во временной группе отодвигает её срок. Обычную группу не
// трогает: у неё срока нет, и появиться он не может.
func (s *Store) BumpCallGroupTTL(ctx context.Context, groupID string, ttl time.Duration) error {
	_, err := s.pool.Exec(ctx, `
		UPDATE groups SET call_ttl_until = now() + make_interval(secs => $2)
		 WHERE group_id = $1 AND call_ttl_until IS NOT NULL AND deleted_at IS NULL`,
		groupID, ttl.Seconds())
	return err
}

// ExpiredCallGroups — временные группы, чей срок вышел и в которых нет идущего звонка.
// Звонок, идущий дольше срока, группу держит: обрывать разговор уборщик не вправе.
func (s *Store) ExpiredCallGroups(ctx context.Context, limit int) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT g.group_id::text FROM groups g
		 WHERE g.call_ttl_until IS NOT NULL AND g.call_ttl_until < now() AND g.deleted_at IS NULL
		   AND NOT EXISTS (SELECT 1 FROM calls c
		                    WHERE c.group_id = g.group_id AND c.type = 'group'
		                      AND c.state IN ('ringing', 'answered'))
		 LIMIT $1`, limit)
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

// DeleteCallGroup удаляет временную группу звонка целиком (решение 1): переписку и ключи
// — насовсем, членства закрываются, сама группа помечается удалённой. Возвращает, кто в
// ней был, — им сказать, что группы больше нет.
//
// Только временную: условие на `call_ttl_until` стоит в самом запросе, и обычную группу
// этот путь не удалит, даже если его позовут с её номером.
func (s *Store) DeleteCallGroup(ctx context.Context, groupID string) ([]string, error) {
	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx) //nolint:errcheck // после Commit это no-op

	ct, err := tx.Exec(ctx, `
		UPDATE groups SET deleted_at = now()
		 WHERE group_id = $1 AND call_ttl_until IS NOT NULL AND deleted_at IS NULL`, groupID)
	if err != nil {
		return nil, err
	}
	if ct.RowsAffected() == 0 {
		return nil, ErrGroupNotFound
	}
	rows, err := tx.Query(ctx, `
		UPDATE memberships SET left_at = now()
		 WHERE target_type = 'group' AND target_id = $1 AND left_at IS NULL
		RETURNING user_id::text`, groupID)
	if err != nil {
		return nil, err
	}
	var members []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		members = append(members, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, q := range []string{
		`DELETE FROM group_messages WHERE group_id = $1`,
		// Обёртки уходят каскадом от истории ключей.
		`DELETE FROM group_key_history WHERE group_id = $1`,
	} {
		if _, err := tx.Exec(ctx, q, groupID); err != nil {
			return nil, err
		}
	}
	return members, tx.Commit(ctx)
}
