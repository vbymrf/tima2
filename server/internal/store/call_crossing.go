// Встречный вызов (заказчик 2026-11-20: «соединяем»; отчёты 47H7, DZKC, LF57 — двое набрали друг друга в одну
// секунду, и оба услышали «занят»). Отдельным типом, а не методами `*Store`: у хранилища бюджет методов.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// CallCrossing — поиск встречного вызова и замок пары на время «проверить и завести».
type CallCrossing struct{ s *Store }

// CallCrossingOf — встречные вызовы поверх хранилища.
func CallCrossingOf(s *Store) CallCrossing { return CallCrossing{s: s} }

// Lock — замок пары собеседников (порядок не важен) до вызова возвращённой функции.
//
// Без него два нажатия в одну секунду проходят проверку встречного оба, пока ни один звонок ещё не заведён, — и
// заводят по звонку: каждый ждёт в своей комнате, а другой звонок у него отклоняется как «занят». Замок — в базе, а не
// в процессе: так он верен и при нескольких экземплярах сервера.
func (c CallCrossing) Lock(ctx context.Context, a, b string) (func(), error) {
	if b < a {
		a, b = b, a
	}
	key := "call-pair:" + a + ":" + b
	conn, err := c.s.pool.Acquire(ctx)
	if err != nil {
		return nil, err
	}
	if _, err := conn.Exec(ctx, `SELECT pg_advisory_lock(hashtextextended($1, 0))`, key); err != nil {
		conn.Release()
		return nil, err
	}
	return func() {
		// Своим контекстом: запрос мог уже закончиться, а замок обязан сняться.
		_, _ = conn.Exec(context.Background(), `SELECT pg_advisory_unlock(hashtextextended($1, 0))`, key)
		conn.Release()
	}, nil
}

// Ringing — личный вызов от `from` к `to`, который сейчас звонит; "" — такого нет.
//
// Срок — тот же, что у «занят» (`HasLiveCall`): вызов старше двух минут не звонит, даже если строку не закрыли.
func (c CallCrossing) Ringing(ctx context.Context, from, to string) (string, error) {
	var id string
	err := c.s.pool.QueryRow(ctx, `
		SELECT call_id FROM calls
		WHERE initiator_id = $1 AND peer_id = $2 AND type = 'direct'
		  AND state = 'ringing' AND ended_at IS NULL
		  AND created_at > now() - interval '2 minutes'
		ORDER BY created_at DESC LIMIT 1`, from, to).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	return id, err
}

// Close — закрыть звонок, если он ещё открыт; `true` — закрыл именно этот вызов.
//
// Конец звонка приходит двумя путями сразу: `/end` того, кто положил трубку, и вебхук LiveKit об ушедшем — комнату
// закрыл тот же `/end`. Оба читали «звонит», оба писали и **оба объявляли** сторонам. Запись условная и вторая строку
// не меняла, а объявление уходило: realme отклонил вызов — `declined`, следом `cancelled` от вебхука, и у отклонившего
// встало «пропущенный» (2026-11-20). Объявляет только тот, чья запись закрыла звонок.
func (c CallCrossing) Close(ctx context.Context, callID, state, endedBy string, at time.Time) (bool, error) {
	return setCallStateTold(ctx, c.s, callID, state, endedBy, at)
}
