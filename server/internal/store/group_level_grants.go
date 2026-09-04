// Доступ по разрешению и срок участия (ADR-0019 §8–§9).
//
// Срок везде считается ЭПОХАМИ вида «2026-09»: строки этого формата сравниваются
// лексикографически так же, как даты — хронологически, поэтому проверка «ещё действует»
// умещается в само условие запроса и не требует ни разбора строки, ни фоновой задачи.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// ErrGrantNotFound — просьбы или разрешения от этого человека в этой группе нет.
var ErrGrantNotFound = errors.New("разрешение не найдено")

// LevelGrant — строка доступа: и просьба, и выданное разрешение.
type LevelGrant struct {
	UserID     string
	Level      int16
	State      string // asked | granted | declined
	UntilEpoch string // пусто = бессрочно
	AskedAt    time.Time
	GrantedAt  *time.Time
}

// AskForLevel — попросить доступ. Повторная просьба обновляет строку, отказ не вечен:
// то же правило, что у заявки на вступление.
func (s *Store) AskForLevel(ctx context.Context, groupID, userID string, level int16) (string, error) {
	var state string
	err := s.pool.QueryRow(ctx, `
		INSERT INTO group_level_grants (group_id, user_id, level, state)
		VALUES ($1, $2, $3, 'asked')
		ON CONFLICT (group_id, user_id) DO UPDATE
		   SET state = CASE WHEN group_level_grants.state = 'granted'
		                    THEN 'granted' ELSE 'asked' END,
		       asked_at = now()
		RETURNING state`, groupID, userID, level).Scan(&state)
	return state, err
}

// GrantLevel — выдать или отозвать доступ.
//
// untilEpoch пустой означает «бессрочно». Отзыв — это `state = 'declined'`, а не удаление
// строки: человек должен видеть, что ему отказали, иначе попросит снова.
func (s *Store) GrantLevel(ctx context.Context, groupID, userID string, level int16, untilEpoch, grantedBy string, grant bool) error {
	state := "declined"
	if grant {
		state = "granted"
	}
	var until, by any
	if untilEpoch != "" {
		until = untilEpoch
	}
	if grantedBy != "" {
		by = grantedBy
	}
	tag, err := s.pool.Exec(ctx, `
		INSERT INTO group_level_grants (group_id, user_id, level, state, until_epoch, granted_at, granted_by)
		VALUES ($1, $2, $3, $4, $5, now(), $6)
		ON CONFLICT (group_id, user_id) DO UPDATE
		   SET level = $3, state = $4, until_epoch = $5, granted_at = now(), granted_by = $6`,
		groupID, userID, level, state, until, by)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrGrantNotFound
	}
	return nil
}

// ListLevelGrants — состав глазами админа: кому открыто и кто просит.
func (s *Store) ListLevelGrants(ctx context.Context, groupID string) ([]LevelGrant, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT user_id, level, state, COALESCE(until_epoch, ''), asked_at, granted_at
		  FROM group_level_grants
		 WHERE group_id = $1
		 ORDER BY asked_at DESC`, groupID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []LevelGrant
	for rows.Next() {
		var g LevelGrant
		if err := rows.Scan(&g.UserID, &g.Level, &g.State, &g.UntilEpoch, &g.AskedAt, &g.GrantedAt); err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}

// GrantedLevelFor — до какого уровня человеку открыто ПРЯМО СЕЙЧАС.
//
// Возвращает -1, если разрешения нет или срок вышел. Срок проверяется в самом запросе
// сравнением с текущей эпохой: `until_epoch >= to_char(now(), 'YYYY-MM')`. Отсюда
// главное свойство: **истечение не требует ни задачи по расписанию, ни уборки** —
// просроченная строка просто перестаёт подходить под условие.
//
// Прочитанное до истечения остаётся у человека на устройстве: срок закрывает будущее, а
// не прошлое (ADR-0019 §8).
func (s *Store) GrantedLevelFor(ctx context.Context, groupID, userID string) (int16, error) {
	var level int16
	err := s.pool.QueryRow(ctx, `
		SELECT level FROM group_level_grants
		 WHERE group_id = $1 AND user_id = $2 AND state = 'granted'
		   AND (until_epoch IS NULL OR until_epoch >= to_char(now(), 'YYYY-MM'))`,
		groupID, userID).Scan(&level)
	if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
		return -1, nil
	}
	return level, err
}

// SetMembershipTerm — срок участия в группе, в эпохах. Пустая строка снимает срок.
func (s *Store) SetMembershipTerm(ctx context.Context, groupID, userID, untilEpoch string) error {
	var until any
	if untilEpoch != "" {
		until = untilEpoch
	}
	tag, err := s.pool.Exec(ctx, `
		UPDATE memberships SET until_epoch = $3
		 WHERE target_type = 'group' AND target_id = $1 AND user_id = $2 AND left_at IS NULL`,
		groupID, userID, until)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return ErrNotMember
	}
	return nil
}

// ExpireMemberships выводит из группы тех, чей срок вышел, и говорит, скольких вывел.
//
// Вызывается там, где сервер и так заметил смену эпохи, — то есть где ключ всё равно
// будет ротироваться. Отдельной задачи по расписанию не заводится: это было условием
// решения о сроках.
//
// Владельца не трогает никогда: группа без владельца — сирота, а срок ему никто не
// ставил бы, но защита стоит здесь, а не в вызывающем.
func (s *Store) ExpireMemberships(ctx context.Context, groupID string) (int64, error) {
	tag, err := s.pool.Exec(ctx, `
		UPDATE memberships SET left_at = now(), until_epoch = NULL
		 WHERE target_type = 'group' AND target_id = $1 AND left_at IS NULL
		   AND role <> 'owner'
		   AND until_epoch IS NOT NULL
		   AND until_epoch < to_char(now(), 'YYYY-MM')`, groupID)
	if err != nil {
		return 0, err
	}
	return tag.RowsAffected(), nil
}
