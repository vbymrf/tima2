// Лента состояний (ПЛАН-(ОП)-ОТМЕТОК-И-ПРИСУТСТВИЯ, ОП0–ОП4; миграция 0072): «доставлено»,
// «прочитано», «печатает», «в сети» — последняя правда, а не история.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

// bumpState — общий кусок записи: следующий номер ленты человека. Вставляется в начало
// каждого запроса записи CTE `top`, чтобы номер и строка менялись одним запросом.
const bumpState = `
	WITH top AS (
		INSERT INTO state_tops (user_id, rev) VALUES ($1, 1)
		ON CONFLICT (user_id) DO UPDATE SET rev = state_tops.rev + 1
		RETURNING rev
	)`

// Receipt — «доставлено» и «прочитано» в личной переписке, в списке отправителя.
type Receipt struct {
	OwnerID     string
	ChatID      string
	PeerID      string
	DeliveredMs int64
	ReadMs      int64
}

// SetReceipt поднимает отметки отправителя ownerID в чате chatID (только вверх; прочитано
// включает доставлено). Возвращает номер ленты, 0 — ничего не изменилось.
func (s *Store) SetReceipt(ctx context.Context, r Receipt) (int64, error) {
	if r.ReadMs > r.DeliveredMs {
		r.DeliveredMs = r.ReadMs
	}
	// Без изменения номер не тратим: тот же «доставлено до» с соседнего устройства — не новость.
	var cur Receipt
	err := s.pool.QueryRow(ctx,
		`SELECT delivered_ms, read_ms FROM chat_receipts WHERE owner_id = $1 AND chat_id = $2`,
		r.OwnerID, r.ChatID).Scan(&cur.DeliveredMs, &cur.ReadMs)
	if err == nil && cur.DeliveredMs >= r.DeliveredMs && cur.ReadMs >= r.ReadMs {
		return 0, nil
	}
	var rev int64
	err = s.pool.QueryRow(ctx, bumpState+`
		INSERT INTO chat_receipts (owner_id, chat_id, peer_id, delivered_ms, read_ms, rev)
		SELECT $1, $2, $3, $4, $5, rev FROM top
		ON CONFLICT (owner_id, chat_id) DO UPDATE SET
			delivered_ms = GREATEST(chat_receipts.delivered_ms, EXCLUDED.delivered_ms),
			read_ms = GREATEST(chat_receipts.read_ms, EXCLUDED.read_ms),
			rev = EXCLUDED.rev, updated_at = now()
		RETURNING rev`, r.OwnerID, r.ChatID, r.PeerID, r.DeliveredMs, r.ReadMs).Scan(&rev)
	return rev, err
}

// AckedDeliveries — что устройство только что забрало: личные сообщения из событий журнала
// после прежнего курсора и до upTo, по чату и отправителю — позднейшее время написания.
// Свои сообщения (копии с других устройств аккаунта) не в счёт.
func (s *Store) AckedDeliveries(ctx context.Context, deviceID, userID string, upTo int64) ([]Receipt, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT pm.sender_id::text, pm.chat_id::text, MAX(pm.created_at_unix_ms)
		  FROM device_events e
		  JOIN personal_messages pm
		    ON pm.chat_id = (e.payload->>'chat_id')::uuid
		   AND pm.message_id = (e.payload->>'message_id')::bigint
		 WHERE e.device_id = $1 AND e.event_type = 'message.new'
		   AND e.event_id > COALESCE((SELECT cursor FROM sync_cursors WHERE device_id = $1), 0)
		   AND e.event_id <= $3
		   AND pm.sender_id <> $2::uuid
		 GROUP BY 1, 2`, deviceID, userID, upTo)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
		return nil, err
	}
	defer rows.Close()
	var out []Receipt
	for rows.Next() {
		r := Receipt{PeerID: userID}
		if err := rows.Scan(&r.OwnerID, &r.ChatID, &r.DeliveredMs); err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// ChatPeer — собеседник личной переписки для отметки «прочитано»: тот, кто в ней писал и не
// я. Пусто — переписки нет или я в ней не участвую: мне сообщения из неё не приходили и я в
// неё не писал.
func (s *Store) ChatPeer(ctx context.Context, chatID, userID, deviceID string) (string, error) {
	var peer string
	err := s.pool.QueryRow(ctx, `
		SELECT pm.sender_id::text FROM personal_messages pm
		 WHERE pm.chat_id = $1 AND pm.sender_id <> $2::uuid
		   AND (EXISTS (SELECT 1 FROM personal_messages mine WHERE mine.chat_id = $1 AND mine.sender_id = $2::uuid)
		     OR EXISTS (SELECT 1 FROM device_events e WHERE e.device_id = $3 AND e.event_type = 'message.new'
		                  AND e.payload->>'chat_id' = $1::text))
		 LIMIT 1`, chatID, userID, deviceID).Scan(&peer)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) || isBadUUID(err) {
			return "", nil
		}
		return "", err
	}
	return peer, nil
}

// SetTyping — «from печатает в чате» в списке собеседника owner до untilMs; 0 — перестал.
func (s *Store) SetTyping(ctx context.Context, ownerID, fromID, chatID string, untilMs int64) (int64, error) {
	var rev int64
	err := s.pool.QueryRow(ctx, bumpState+`
		INSERT INTO typing_states (owner_id, from_id, chat_id, until_ms, rev)
		SELECT $1, $2, $3, $4, rev FROM top
		ON CONFLICT (owner_id, from_id) DO UPDATE SET
			chat_id = EXCLUDED.chat_id, until_ms = EXCLUDED.until_ms, rev = EXCLUDED.rev, updated_at = now()
		RETURNING rev`, ownerID, fromID, chatID, untilMs).Scan(&rev)
	return rev, err
}

// Presence — «в сети» человека: хоть одно устройство на экране и отзывалось не позже
// окна; иначе — когда ушёл.
type Presence struct {
	Online     bool
	UntilMs    int64 // до когда «в сети» верно без нового кадра
	LastSeenMs int64
}

// SetDevicePresence — устройство вышло на экран или ушло с него (`foreground`), либо пропало
// (`gone` — соединение закрылось). Возвращает «в сети» человека после изменения и было ли
// оно до. Ушло последнее устройство — пишется «был(а)».
func (s *Store) SetDevicePresence(ctx context.Context, deviceID, userID string, foreground, gone bool, window time.Duration) (now Presence, was bool, err error) {
	was, err = onlineNow(ctx, s, userID, window)
	if err != nil {
		return Presence{}, false, err
	}
	if gone {
		_, err = s.pool.Exec(ctx, `UPDATE device_presence SET foreground = false WHERE device_id = $1`, deviceID)
	} else {
		_, err = s.pool.Exec(ctx, `
			INSERT INTO device_presence (device_id, user_id, foreground, seen_at) VALUES ($1, $2, $3, now())
			ON CONFLICT (device_id) DO UPDATE SET foreground = EXCLUDED.foreground, seen_at = now()`,
			deviceID, userID, foreground)
	}
	if err != nil {
		return Presence{}, was, err
	}
	online, err := onlineNow(ctx, s, userID, window)
	if err != nil {
		return Presence{}, was, err
	}
	if was && !online {
		_, err = s.pool.Exec(ctx, `UPDATE users SET last_seen_at = now() WHERE user_id = $1`, userID)
		if err != nil {
			return Presence{}, was, err
		}
	}
	now, err = s.UserPresence(ctx, userID, window)
	return now, was, err
}

// onlineNow — функция, а не метод: часть SetDevicePresence, отдельно её никто не зовёт.
func onlineNow(ctx context.Context, s *Store, userID string, window time.Duration) (bool, error) {
	var on bool
	err := s.pool.QueryRow(ctx, `
		SELECT EXISTS (SELECT 1 FROM device_presence
		                WHERE user_id = $1 AND foreground AND seen_at > now() - make_interval(secs => $2))`,
		userID, window.Seconds()).Scan(&on)
	if isBadUUID(err) {
		return false, nil
	}
	return on, err
}

// UserPresence — «в сети» человека сейчас и «был(а)».
func (s *Store) UserPresence(ctx context.Context, userID string, window time.Duration) (Presence, error) {
	var p Presence
	var seen, lastSeen *time.Time
	err := s.pool.QueryRow(ctx, `
		SELECT (SELECT MAX(seen_at) FROM device_presence
		         WHERE user_id = $1 AND foreground AND seen_at > now() - make_interval(secs => $2)),
		       (SELECT last_seen_at FROM users WHERE user_id = $1)`,
		userID, window.Seconds()).Scan(&seen, &lastSeen)
	if err != nil {
		if isBadUUID(err) {
			return Presence{}, nil
		}
		return Presence{}, err
	}
	if seen != nil {
		p.Online = true
		p.UntilMs = seen.Add(window).UnixMilli()
	}
	if lastSeen != nil {
		p.LastSeenMs = lastSeen.UnixMilli()
	}
	return p, nil
}

// WatchPresence — устройство смотрит переписку с target до until; until нулевое — перестало.
func (s *Store) WatchPresence(ctx context.Context, watcherDevice, watcherID, targetID string, until time.Time) error {
	if until.IsZero() {
		_, err := s.pool.Exec(ctx,
			`DELETE FROM presence_watchers WHERE watcher_device = $1 AND target_id = $2`, watcherDevice, targetID)
		if isBadUUID(err) {
			return nil
		}
		return err
	}
	_, err := s.pool.Exec(ctx, `
		INSERT INTO presence_watchers (watcher_device, watcher_id, target_id, until_at) VALUES ($1, $2, $3, $4)
		ON CONFLICT (watcher_device, target_id) DO UPDATE SET until_at = EXCLUDED.until_at`,
		watcherDevice, watcherID, targetID, until)
	if isBadUUID(err) {
		return nil
	}
	return err
}

// PresenceWatchers — кто сейчас смотрит переписку с target (люди, без повторов).
func (s *Store) PresenceWatchers(ctx context.Context, targetID string) ([]string, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT DISTINCT watcher_id::text FROM presence_watchers WHERE target_id = $1 AND until_at > now()`, targetID)
	if err != nil {
		if isBadUUID(err) {
			return nil, nil
		}
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

// SetPresenceState — копия «в сети» target у смотрящего owner.
func (s *Store) SetPresenceState(ctx context.Context, ownerID, targetID string, p Presence) (int64, error) {
	var rev int64
	err := s.pool.QueryRow(ctx, bumpState+`
		INSERT INTO presence_states (owner_id, target_id, online, until_ms, last_seen_ms, rev)
		SELECT $1, $2, $3, $4, $5, rev FROM top
		ON CONFLICT (owner_id, target_id) DO UPDATE SET
			online = EXCLUDED.online, until_ms = EXCLUDED.until_ms, last_seen_ms = EXCLUDED.last_seen_ms,
			rev = EXCLUDED.rev, updated_at = now()
		RETURNING rev`, ownerID, targetID, p.Online, p.UntilMs, p.LastSeenMs).Scan(&rev)
	return rev, err
}

// StateRow — одна строка ленты состояний: вид и поля своего вида.
type StateRow struct {
	Kind        string // receipt · typing · presence · top · notify
	Rev         int64
	ChatID      string // receipt, typing: переписка; top, notify: сущность
	PeerID      string // receipt: собеседник; typing: кто печатает; presence: о ком
	DeliveredMs int64
	ReadMs      int64
	UntilMs     int64
	Online      bool
	LastSeenMs  int64
	EntityKind  string // top, notify: group · channel · chat · community
	TopID       int64
	TopAtMs     int64
	Unread      int64 // top: непрочитанного после отметки, не больше 100
	Off         bool  // notify: уведомления отключены
}

// ListStates — строки ленты человека после after и вершина ленты.
func (s *Store) ListStates(ctx context.Context, userID string, after int64) ([]StateRow, int64, error) {
	var top int64
	if err := s.pool.QueryRow(ctx,
		`SELECT COALESCE((SELECT rev FROM state_tops WHERE user_id = $1), 0)`, userID).Scan(&top); err != nil {
		if isBadUUID(err) {
			return nil, 0, nil
		}
		return nil, 0, err
	}
	// Непрочитанное у вершины считается при чтении, а не хранится: отметка прочтения и новое
	// сообщение меняют его с двух сторон, и хранимое число разошлось бы с правдой.
	rows, err := s.pool.Query(ctx, `
		SELECT 'receipt', rev, chat_id::text, peer_id::text, delivered_ms, read_ms, 0::bigint, false, 0::bigint,
		       '', 0::bigint, 0::bigint, 0::bigint, false
		  FROM chat_receipts WHERE owner_id = $1 AND rev > $2
		UNION ALL
		SELECT 'typing', rev, chat_id::text, from_id::text, 0, 0, until_ms, false, 0, '', 0, 0, 0, false
		  FROM typing_states WHERE owner_id = $1 AND rev > $2
		UNION ALL
		SELECT 'presence', rev, '', target_id::text, 0, 0, until_ms, online, last_seen_ms, '', 0, 0, 0, false
		  FROM presence_states WHERE owner_id = $1 AND rev > $2
		UNION ALL
		SELECT 'top', t.rev, t.entity_id::text, '', 0, 0, 0, false, 0, t.kind, t.top_id, t.top_at_ms,
		       CASE t.kind
		         WHEN 'channel' THEN (SELECT count(*) FROM (SELECT 1 FROM channel_posts p
		              WHERE p.channel_id = t.entity_id AND p.post_id > COALESCE(r.read_id, 0)
		                AND NOT p.deleted AND p.author_id <> $1 LIMIT 100) x)
		         ELSE (SELECT count(*) FROM (SELECT 1 FROM group_messages m
		              WHERE m.group_id = t.entity_id AND m.message_id > COALESCE(r.read_id, 0)
		                AND m.sender_id <> $1 LIMIT 100) x)
		       END,
		       false
		  FROM entity_tops t
		  LEFT JOIN entity_reads r ON r.owner_id = t.owner_id AND r.kind = t.kind AND r.entity_id = t.entity_id
		 WHERE t.owner_id = $1 AND t.rev > $2
		UNION ALL
		SELECT 'notify', rev, entity_id::text, '', 0, 0, 0, false, 0, kind, 0, 0, 0, off
		  FROM notify_settings WHERE owner_id = $1 AND rev > $2
		ORDER BY 2`, userID, after)
	if err != nil {
		return nil, top, err
	}
	defer rows.Close()
	var out []StateRow
	for rows.Next() {
		var r StateRow
		if err := rows.Scan(&r.Kind, &r.Rev, &r.ChatID, &r.PeerID, &r.DeliveredMs, &r.ReadMs,
			&r.UntilMs, &r.Online, &r.LastSeenMs, &r.EntityKind, &r.TopID, &r.TopAtMs, &r.Unread, &r.Off); err != nil {
			return nil, top, err
		}
		out = append(out, r)
	}
	return out, top, rows.Err()
}
