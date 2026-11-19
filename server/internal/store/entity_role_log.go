// Журнал ролей сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ6). Отдельным типом: у `*Store` бюджет методов
// (architecture_test).
package store

import (
	"context"
	"time"
)

// RoleLog — журнал «кто, кому, что, когда» у групп, каналов, сообществ.
type RoleLog struct{ s *Store }

// RoleLogOf — журнал ролей поверх хранилища.
func RoleLogOf(s *Store) RoleLog { return RoleLog{s: s} }

// RoleLogEntry — одна запись журнала.
type RoleLogEntry struct {
	ActorID  string    `json:"actor_id"`
	TargetID string    `json:"target_id"`
	Action   string    `json:"action"`
	Detail   string    `json:"detail"`
	At       time.Time `json:"at"`
}

// Note — записать действие. Ошибку журнала вызывающий только печатает: действие уже сделано.
func (l RoleLog) Note(ctx context.Context, kind, entityID, actorID, targetID, action, detail string) error {
	if l.s == nil {
		return nil
	}
	_, err := l.s.pool.Exec(ctx, `
		INSERT INTO entity_role_log (entity_kind, entity_id, actor_id, target_id, action, detail)
		VALUES ($1, $2::uuid, $3::uuid, $4::uuid, $5, $6)`, kind, entityID, actorID, targetID, action, detail)
	return err
}

// Recent — последние записи сущности, новые первыми; не больше limit (1…200).
func (l RoleLog) Recent(ctx context.Context, kind, entityID string, limit int) ([]RoleLogEntry, error) {
	if limit <= 0 || limit > 200 {
		limit = 200
	}
	rows, err := l.s.pool.Query(ctx, `
		SELECT actor_id::text, target_id::text, action, detail, at FROM entity_role_log
		 WHERE entity_kind = $1 AND entity_id = $2::uuid ORDER BY log_id DESC LIMIT $3`, kind, entityID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []RoleLogEntry{}
	for rows.Next() {
		var e RoleLogEntry
		if err := rows.Scan(&e.ActorID, &e.TargetID, &e.Action, &e.Detail, &e.At); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, rows.Err()
}
