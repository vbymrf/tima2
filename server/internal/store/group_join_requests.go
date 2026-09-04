// Заявки на вступление (ADR-0018 п. 7). Единственное действие с чужой личной группой.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ErrJoinRequestNotFound — заявки от этого человека в этой группе нет.
var ErrJoinRequestNotFound = errors.New("заявка не найдена")

// JoinRequest — строка очереди админа.
type JoinRequest struct {
	UserID     string
	State      string // pending | accepted | declined
	AskedAt    time.Time
	AnsweredAt *time.Time
	AnsweredBy string
}

// AskToJoin создаёт или обновляет заявку.
//
// Повторная просьба обновляет строку, а не заводит вторую: очередь админа — это список
// людей, а не журнал попыток. Отказ при этом сбрасывается обратно в 'pending' —
// человек имеет право попросить снова, когда обстоятельства изменились, иначе первый
// отказ становится вечным.
//
// Возвращает состояние, в котором заявка оказалась.
func (s *Store) AskToJoin(ctx context.Context, groupID, userID string) (string, error) {
	var state string
	err := s.pool.QueryRow(ctx, `
		INSERT INTO group_join_requests (group_id, user_id, state)
		VALUES ($1, $2, 'pending')
		ON CONFLICT (group_id, user_id) DO UPDATE
		   SET state = CASE WHEN group_join_requests.state = 'accepted'
		                    THEN 'accepted' ELSE 'pending' END,
		       asked_at = CASE WHEN group_join_requests.state = 'accepted'
		                       THEN group_join_requests.asked_at ELSE now() END,
		       answered_at = NULL, answered_by = NULL
		RETURNING state`, groupID, userID).Scan(&state)
	return state, err
}

// ListJoinRequests — очередь админа. Только ожидающие: отвеченные ему не нужны, а
// просившему своё состояние отдаётся отдельным запросом.
func (s *Store) ListJoinRequests(ctx context.Context, groupID string) ([]JoinRequest, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT user_id, state, asked_at, answered_at, COALESCE(answered_by::text, '')
		  FROM group_join_requests
		 WHERE group_id = $1 AND state = 'pending'
		 ORDER BY asked_at DESC`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []JoinRequest
	for rows.Next() {
		var jr JoinRequest
		if err := rows.Scan(&jr.UserID, &jr.State, &jr.AskedAt, &jr.AnsweredAt, &jr.AnsweredBy); err != nil {
			return nil, err
		}
		out = append(out, jr)
	}
	return out, rows.Err()
}

// AnswerJoinRequest отмечает заявку принятой или отклонённой.
//
// Само добавление в состав делает вызывающий: заявка и членство — разные вещи, и
// связывать их в одном SQL значило бы обойти проверки роли, которые живут в API.
func (s *Store) AnswerJoinRequest(ctx context.Context, groupID, userID, state, answeredBy string) error {
	// Пустая строка — не UUID: колонка примет NULL, а не «неизвестно кто».
	var by any
	if answeredBy != "" {
		by = answeredBy
	}
	tag, err := s.pool.Exec(ctx, `
		UPDATE group_join_requests
		   SET state = $3, answered_at = now(), answered_by = $4
		 WHERE group_id = $1 AND user_id = $2 AND state = 'pending'`,
		groupID, userID, state, by)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrJoinRequestNotFound
	}
	return nil
}

// MyJoinRequestState — что стало с моей просьбой. Пустая строка означает «не просил».
//
// Ради этого метода и заведено состояние 'declined': отказ виден просившему, иначе он
// не отличит его от «не дошло» и попросит снова.
func (s *Store) MyJoinRequestState(ctx context.Context, groupID, userID string) (string, error) {
	var state string
	err := s.pool.QueryRow(ctx,
		`SELECT state FROM group_join_requests WHERE group_id = $1 AND user_id = $2`,
		groupID, userID).Scan(&state)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", nil
	}
	return state, err
}
