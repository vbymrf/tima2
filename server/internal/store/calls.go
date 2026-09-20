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

// HasLiveCall — **звонит ли сейчас у человека телефон**. Не «разговаривает ли он».
//
// ── ПОЧЕМУ ТОЛЬКО ЗВОНЯЩИЙ, И ЭТО ИСПРАВЛЕНИЕ ──────────────────────────────
//
// Сначала сюда входило и состояние `answered` — «человек разговаривает». Это оказалось
// ошибкой, и дорогой: 2026-09-20 один незакрытый звонок пятичасовой давности сделал
// обоих участников недоступными, и позвонить не мог никто. Строка осталась открытой
// потому, что закрыть её было некому — приложения переустанавливались посреди разговора,
// и ни клиент, ни вебхук LiveKit до сервера не дошли.
//
// **Корень в том, что состояние `answered` закрывается снаружи, а не истекает само.**
// Приложение убили, телефон уснул, вебхук потерялся — строка живёт вечно. Любая проверка,
// опирающаяся на неё, превращает утечку в запрет, и запрет тем длиннее, чем шире окно.
//
// `ringing` этим не страдает: он живёт две минуты и истекает сам, потому что и настоящий
// вызов длится сорок пять секунд.
//
// **Чем заменено «он разговаривает».** Тем, кто это знает наверняка, — самим телефоном
// собеседника: получив вызов во время разговора, он заканчивает его с причиной `busy`, и
// звонящий слышит «занят» от того, кто действительно занят. См. `endCall`.
//
// Ошибаться этот запрос обязан в сторону «свободен»: ложное «занято» отнимает звонок,
// которого человек ждал, а ложное «свободен» даёт лишний вызов — и тот виден.
func (s *Store) HasLiveCall(ctx context.Context, userID string) (bool, error) {
	var busy bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS (
			SELECT 1 FROM calls
			WHERE (initiator_id = $1 OR peer_id = $1)
			  AND ended_at IS NULL
			  AND state = 'ringing'
			  AND created_at > now() - interval '2 minutes'
		)`, userID).Scan(&busy)
	return busy, err
}

// OpenCalls — звонки, которые числятся идущими дольше указанного срока.
//
// Их закрывать некому: состояние в базе меняет тот, кто звонок закончил, а он мог
// исчезнуть — приложение убили, телефон уснул, вебхук LiveKit потерялся. Строка тогда
// остаётся открытой навсегда, и у неё нет ни конца, ни длительности: это данные, которые
// лгут. Журнал звонков прочитает такую как «идёт с позавчера».
//
// Возраст здесь — только отбор кандидатов. **Решает не он, а живая комната**: уборщик
// спрашивает LiveKit и закрывает лишь то, чего там уже нет.
func (s *Store) OpenCalls(ctx context.Context, olderThanSec int64, limit int) ([]Call, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT call_id, room, kind, initiator_id, peer_id, state
		FROM calls
		WHERE ended_at IS NULL
		  AND state IN ('ringing','answered')
		  AND created_at < now() - make_interval(secs => $1)
		ORDER BY created_at
		LIMIT $2`, olderThanSec, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Call
	for rows.Next() {
		var c Call
		if err := rows.Scan(&c.CallID, &c.Room, &c.Kind, &c.InitiatorID, &c.PeerID, &c.State); err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, rows.Err()
}

// GCCalls — удалить давно законченные звонки.
//
// Таблица не чистилась вовсе: в списке уборщика её не было. Строка звонка мелкая, но
// растёт навсегда, а хранить, чем кто с кем говорил год назад, мы не обещали никому.
func (s *Store) GCCalls(ctx context.Context, olderThanSec int64) (int64, error) {
	tag, err := s.pool.Exec(ctx, `
		DELETE FROM calls
		WHERE ended_at IS NOT NULL
		  AND ended_at < now() - make_interval(secs => $1)`, olderThanSec)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
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
	case "ended", "missed", "busy":
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

// IsSpeaker — есть ли у пользователя право говорить (владелец — всегда спикер).
func (s *Store) IsSpeaker(ctx context.Context, roomID, ownerID, userID string) (bool, error) {
	if userID == ownerID {
		return true, nil
	}
	var ok bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS(SELECT 1 FROM voice_speakers WHERE room_id=$1 AND user_id=$2)`, roomID, userID).Scan(&ok)
	return ok, err
}

func (s *Store) AddSpeaker(ctx context.Context, roomID, userID string) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO voice_speakers (room_id, user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING`, roomID, userID)
	return err
}

func (s *Store) RemoveSpeaker(ctx context.Context, roomID, userID string) error {
	_, err := s.pool.Exec(ctx, `DELETE FROM voice_speakers WHERE room_id=$1 AND user_id=$2`, roomID, userID)
	return err
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
