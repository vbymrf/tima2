package store

import (
	"context"
	"errors"
	"time"

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

	// ── ВРЕМЕНА И «КТО ПОЛОЖИЛ ТРУБКУ» ──────────────────────────────────────
	//
	// Нужны снимку звонка (`GET /calls/{id}`, П1) и строке журнала. Лежат здесь, а
	// не в отдельном типе с отдельным запросом: два вида одного и того же однажды
	// разойдутся, и разойдутся молча.
	//
	// Заполняются только `GetCall`. `OpenCalls` их не читает — уборщику они не
	// нужны, а лишние столбцы в его выборке ничего не объясняют.
	EndedBy    string
	CreatedAt  time.Time
	AnsweredAt time.Time
	EndedAt    time.Time
}

// Row — строка звонка на проводе: то, что видит клиент и в списке, и в снимке.
//
// Метод, а не ещё один запрос: `GET /calls/{id}` обязан отдавать ту же строку, что
// `GET /calls`, и собирать её вторым куском кода нельзя.
func (c Call) Row() CallRow {
	return CallRow{
		CallID:      c.CallID,
		Kind:        c.Kind,
		State:       c.State,
		InitiatorID: c.InitiatorID,
		PeerID:      c.PeerID,
		EndedBy:     c.EndedBy,
		CreatedAt:   c.CreatedAt,
		AnsweredAt:  c.AnsweredAt,
		EndedAt:     c.EndedAt,
	}
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
// **Сегодня не зовётся: `calls_journal_days` равен нулю, и уборщик задачу пропускает.**
// Строка звонка это одни метаданные, а они не удаляются никогда — то же правило, что у
// сообщений (`PurgeMessageContent`: стирается содержимое, строка живёт).
//
// Метод оставлен, а не выброшен: срок живёт строкой в `retention_policy`, и поставить
// его больше нуля — правка строки, а не пересборка. Выбросить уборку совсем значило бы
// сделать это решение невозвратным.
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
	// peer_id стал nullable в 0023 ради групповых, ended_by — в 0054. Сканировать
	// их прямо в строку значило бы падать на данных, которые база допускает.
	var peer, endedBy *string
	var answered, ended *time.Time
	err := s.pool.QueryRow(ctx, `
		SELECT call_id, room, kind, initiator_id, peer_id, state,
		       ended_by, created_at, answered_at, ended_at
		FROM calls WHERE call_id = $1`, callID).
		Scan(&c.CallID, &c.Room, &c.Kind, &c.InitiatorID, &peer, &c.State,
			&endedBy, &c.CreatedAt, &answered, &ended)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return c, ErrCallNotFound
	}
	if peer != nil {
		c.PeerID = *peer
	}
	if endedBy != nil {
		c.EndedBy = *endedBy
	}
	if answered != nil {
		c.AnsweredAt = *answered
	}
	if ended != nil {
		c.EndedAt = *ended
	}
	return c, err
}

// SetCallState переводит звонок в новое состояние (answered/ended/missed) с отметкой времени.
//
// `endedBy` — кто положил трубку; пусто, когда этого не знает никто: звонок закрыл
// уборщик или вебхук LiveKit. Пустое здесь — не «неважно», а сведение: строка журнала
// без `ended_by` означает, что звонок бросили, а не закончили.
//
// **Записывается только первый.** `POST /end` зовут обе стороны, и вторая приходит
// через секунды после первой; перезапись стёрла бы ровно то, ради чего поле заведено, —
// кто нажал раньше. Отсюда COALESCE(ended_by, $3), а не наоборот.
func (s *Store) SetCallState(ctx context.Context, callID, state, endedBy string) error {
	col := ""
	switch state {
	case "answered":
		col = ", answered_at = now()"
	case "ended", "missed", "busy", "lost":
		col = ", ended_at = now()"
	}
	var by any
	if endedBy != "" {
		by = endedBy
	}
	_, err := s.pool.Exec(ctx,
		`UPDATE calls SET state = $2`+col+`, ended_by = COALESCE(ended_by, $3::uuid) WHERE call_id = $1`,
		callID, state, by)
	return err
}

// CallRow — строка журнала звонков: всё, что нужно показать, и ничего сверх.
//
// Отдельным типом от Call намеренно. `Call` — звонок, каким его ведёт сигналинг: имя
// комнаты, состояние прямо сейчас, то, по чему выдаётся токен. `CallRow` — про прошлое,
// и ей нужны времена, которых `Call` не носит. Сложить их в один тип значило бы таскать
// имя комнаты LiveKit в список, где оно не нужно никому и выдаёт наружу внутреннее.
type CallRow struct {
	CallID      string
	Kind        string // audio|video — та самая кнопка, которой звонили
	State       string // ringing|answered|ended|missed|busy|lost
	InitiatorID string
	PeerID      string
	EndedBy     string    // кто положил трубку; пусто — некому было, звонок бросили
	CreatedAt   time.Time
	AnsweredAt  time.Time // нулевое — трубку не брали
	EndedAt     time.Time // нулевое — звонок ещё числится идущим
}

// ListCalls — страница журнала звонков человека, новые → старые.
//
// **Только личные (`type = direct`).** Групповой звонок в клиенте не существует вовсе:
// кнопка у него есть, а обратного вызова у кнопки нет намеренно. Строка о звонке,
// которого нельзя совершить, — обещание, а не история. Групповые придут в журнал вместе
// с самим групповым звонком (ПЛАН-ЖУРНАЛА-ЗВОНКОВ.md, решение Ж-В2).
//
// **Отбор по обоим концам.** Человек — сторона звонка, неважно, звонил он или ему;
// журнал у него один. Индексы есть на оба (0014 на peer_id, 0054 на initiator_id).
//
// `before` нулевое — первая страница. Дальше передаётся `created_at` последней отданной
// строки, и отбор строгий: та же строка второй раз не придёт.
func (s *Store) ListCalls(ctx context.Context, userID string, before time.Time, limit int) ([]CallRow, error) {
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	var upto any
	if !before.IsZero() {
		upto = before
	}
	rows, err := s.pool.Query(ctx, `
		SELECT call_id, kind, state, initiator_id, peer_id, ended_by,
		       created_at, answered_at, ended_at
		FROM calls
		WHERE type = 'direct'
		  AND (initiator_id = $1 OR peer_id = $1)
		  AND ($2::timestamptz IS NULL OR created_at < $2::timestamptz)
		ORDER BY created_at DESC
		LIMIT $3`, userID, upto, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]CallRow, 0, limit)
	for rows.Next() {
		var c CallRow
		// peer_id стал nullable в 0023 ради групповых; здесь их нет по отбору, но
		// сканировать в строку значило бы падать на данных, которые база допускает.
		var peer, endedBy *string
		var answered, ended *time.Time
		if err := rows.Scan(&c.CallID, &c.Kind, &c.State, &c.InitiatorID, &peer, &endedBy,
			&c.CreatedAt, &answered, &ended); err != nil {
			return nil, err
		}
		if peer != nil {
			c.PeerID = *peer
		}
		if endedBy != nil {
			c.EndedBy = *endedBy
		}
		if answered != nil {
			c.AnsweredAt = *answered
		}
		if ended != nil {
			c.EndedAt = *ended
		}
		out = append(out, c)
	}
	return out, rows.Err()
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
