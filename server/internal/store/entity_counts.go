// Число участников и подписчиков сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §4б п. 9, СЦ4): шапка страницы. Отдельной
// функцией: у `*Store` бюджет методов (architecture_test).
package store

import "context"

// EntityCounts — номер → число: у группы участники (не вышедшие), у канала и сообщества — подписчики.
func EntityCounts(ctx context.Context, s *Store, kind string, ids []string) (map[string]int, error) {
	out := map[string]int{}
	if len(ids) == 0 {
		return out, nil
	}
	q := `SELECT target_id::text, count(*) FROM memberships
	       WHERE target_type = 'group' AND target_id::text = ANY($1) AND left_at IS NULL GROUP BY target_id`
	switch kind {
	case "channel":
		q = `SELECT channel_id::text, count(*) FROM channel_subscriptions WHERE channel_id::text = ANY($1) GROUP BY channel_id`
	case "community":
		q = `SELECT community_id::text, count(*) FROM community_subscriptions WHERE community_id::text = ANY($1) GROUP BY community_id`
	}
	rows, err := s.pool.Query(ctx, q, ids)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id string
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[id] = n
	}
	return out, rows.Err()
}
