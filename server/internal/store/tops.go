// «Зашли, забрали» и уведомления по настройке на сервере (ПЛАН-(ОУ)-ОПТИМИЗАЦИИ-УВЕДОМЛЕНИЙ;
// миграция 0073). Строки — в ленте состояний 0072.
package store

import (
	"context"
)

// RaisedTop — вершина сущности поднялась у человека: номер его ленты и отключены ли у него
// уведомления этой сущности (тогда сигнал не шлётся — решение 6).
type RaisedTop struct {
	OwnerID string
	Rev     int64
	Off     bool
}

// RaiseTops — новое сообщение или пост в сущности «зашли, забрали»: вершина у каждого из
// owners одним запросом. Вершина только растёт.
func (s *Store) RaiseTops(ctx context.Context, kind, entityID string, owners []string, topID, topAtMs int64) ([]RaisedTop, error) {
	if len(owners) == 0 {
		return nil, nil
	}
	rows, err := s.pool.Query(ctx, `
		WITH top AS (
			INSERT INTO state_tops (user_id, rev)
			SELECT unnest($3::uuid[]), 1
			ON CONFLICT (user_id) DO UPDATE SET rev = state_tops.rev + 1
			RETURNING user_id, rev
		), up AS (
			INSERT INTO entity_tops (owner_id, kind, entity_id, top_id, top_at_ms, rev)
			SELECT user_id, $1, $2, $4, $5, rev FROM top
			ON CONFLICT (owner_id, kind, entity_id) DO UPDATE SET
				top_id = GREATEST(entity_tops.top_id, EXCLUDED.top_id),
				top_at_ms = GREATEST(entity_tops.top_at_ms, EXCLUDED.top_at_ms),
				rev = EXCLUDED.rev, updated_at = now()
			RETURNING owner_id, rev
		)
		SELECT up.owner_id::text, up.rev, COALESCE(n.off, false)
		  FROM up LEFT JOIN notify_settings n
		    ON n.owner_id = up.owner_id AND n.kind = $1 AND n.entity_id = $2`,
		kind, entityID, owners, topID, topAtMs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []RaisedTop
	for rows.Next() {
		var r RaisedTop
		if err := rows.Scan(&r.OwnerID, &r.Rev, &r.Off); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// SetEntityRead — человек дочитал сущность до readID (только вверх). Вершина получает новый
// номер ленты — число непрочитанного на других устройствах пересчитается. 0 — вершины нет.
func (s *Store) SetEntityRead(ctx context.Context, ownerID, kind, entityID string, readID int64) (int64, error) {
	if _, err := s.pool.Exec(ctx, `
		INSERT INTO entity_reads (owner_id, kind, entity_id, read_id) VALUES ($1, $2, $3, $4)
		ON CONFLICT (owner_id, kind, entity_id) DO UPDATE SET read_id = GREATEST(entity_reads.read_id, EXCLUDED.read_id)`,
		ownerID, kind, entityID, readID); err != nil {
		if isBadUUID(err) {
			return 0, nil
		}
		return 0, err
	}
	var rev int64
	err := s.pool.QueryRow(ctx, bumpState+`
		UPDATE entity_tops SET rev = (SELECT rev FROM top), updated_at = now()
		 WHERE owner_id = $1 AND kind = $2 AND entity_id = $3
		RETURNING rev`, ownerID, kind, entityID).Scan(&rev)
	if err != nil {
		// Вершины нет — номер ленты потрачен зря, но это безвредно.
		return 0, nil
	}
	return rev, nil
}

// SetNotify — «Отключить уведомления» у сущности (off) или включить обратно.
func (s *Store) SetNotify(ctx context.Context, ownerID, kind, entityID string, off bool) (int64, error) {
	var rev int64
	err := s.pool.QueryRow(ctx, bumpState+`
		INSERT INTO notify_settings (owner_id, kind, entity_id, off, rev)
		SELECT $1, $2, $3, $4, rev FROM top
		ON CONFLICT (owner_id, kind, entity_id) DO UPDATE SET off = EXCLUDED.off, rev = EXCLUDED.rev, updated_at = now()
		RETURNING rev`, ownerID, kind, entityID, off).Scan(&rev)
	return rev, err
}

// SetDeviceDelivery — способ доставки, заявленный устройством: пусто или «tops».
func (s *Store) SetDeviceDelivery(ctx context.Context, deviceID, mode string) error {
	_, err := s.pool.Exec(ctx, `UPDATE devices SET delivery = $2 WHERE device_id = $1`, deviceID, mode)
	return err
}

// MemberDeliveries — участники открытой группы по способу доставки: устройства по-старому
// (тело в журнал) и люди, у кого есть устройство «вершиной». Человек с двумя устройствами
// разных способов — в обоих списках: каждое получает своим.
func (s *Store) MemberDeliveries(ctx context.Context, groupID, exceptDevice string) (old []string, tops []string, err error) {
	rows, err := s.pool.Query(ctx, `
		SELECT d.device_id::text, d.user_id::text, d.delivery FROM devices d
		JOIN memberships m ON m.target_type = 'group' AND m.target_id = $1
		     AND m.user_id = d.user_id AND m.left_at IS NULL
		WHERE d.revoked_at IS NULL AND d.device_id::text <> $2`, groupID, exceptDevice)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	seen := map[string]bool{}
	for rows.Next() {
		var dev, user, mode string
		if err := rows.Scan(&dev, &user, &mode); err != nil {
			return nil, nil, err
		}
		if mode == "tops" {
			if !seen[user] {
				seen[user] = true
				tops = append(tops, user)
			}
			continue
		}
		old = append(old, dev)
	}
	return old, tops, rows.Err()
}
