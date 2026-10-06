// GC ретеншена (правила — migrations/0008_gc.sql; вызывает internal/worker).
// Интервалы передаются секундами: make_interval на стороне базы, серверное
// время везде одно (now() PostgreSQL).
package store

import (
	"context"
)

// GCDeviceEvents удаляет события старше olderThanSec и двигает watermark
// (max удалённый event_id) — по нему sync.pull отвечает sync.gap.
func (s *Store) GCDeviceEvents(ctx context.Context, olderThanSec int64) (int64, error) {
	var count, maxDeleted int64
	err := s.pool.QueryRow(ctx, `
		WITH del AS (
			DELETE FROM device_events
			WHERE created_at < now() - make_interval(secs => $1)
			RETURNING event_id
		)
		SELECT COUNT(*), COALESCE(MAX(event_id), 0) FROM del`, olderThanSec).Scan(&count, &maxDeleted)
	if err != nil || maxDeleted == 0 {
		return count, err
	}
	_, err = s.pool.Exec(ctx, `
		INSERT INTO gc_state (key, value) VALUES ('events_watermark', $1)
		ON CONFLICT (key)
		DO UPDATE SET value = GREATEST(gc_state.value, EXCLUDED.value), updated_at = now()`,
		maxDeleted)
	return count, err
}

// GCWatermark — max удалённый GC event_id (0 — GC ещё ничего не удалял).
func (s *Store) GCWatermark(ctx context.Context) (int64, error) {
	var v int64
	err := s.pool.QueryRow(ctx,
		`SELECT COALESCE((SELECT value FROM gc_state WHERE key = 'events_watermark'), 0)`).Scan(&v)
	return v, err
}

// MaxDeviceEventID — последний event_id устройства (next_cursor после sync.gap).
func (s *Store) MaxDeviceEventID(ctx context.Context, deviceID string) (int64, error) {
	var v int64
	err := s.pool.QueryRow(ctx,
		`SELECT COALESCE(MAX(event_id), 0) FROM device_events WHERE device_id = $1`, deviceID).Scan(&v)
	return v, err
}

// GCPersonalWrappedKeys удаляет обёртки конвертов по эпохе (ПЛАН-(ПС) ПС2, Р3, Р5): обёртка
// живёт до конца календарного месяца сообщения (UTC; эпоха депозитария «чат × месяц») плюс
// graceSec. Сообщение исчезает из выдачи истории (JOIN по обёртке) — дальше история только из
// копии ключей; конверт с escrow остаётся до escrow-архива. Короче обёртка — меньше прошлого
// откроет ключ устройства, утёкший потом.
func (s *Store) GCPersonalWrappedKeys(ctx context.Context, graceSec int64) (int64, error) {
	ct, err := s.pool.Exec(ctx, `
		DELETE FROM personal_message_keys k
		USING personal_messages m
		WHERE m.chat_id = k.chat_id AND m.message_id = k.message_id
		  AND date_trunc('month', m.received_at AT TIME ZONE 'UTC') + interval '1 month'
		      < (now() AT TIME ZONE 'UTC') - make_interval(secs => $1)`, graceSec)
	return ct.RowsAffected(), err
}

// GCGroupWrappedKeys удаляет wrapped_GK версий, которые сменила следующая версия больше
// graceSec назад (ПС2, Р5); escrow версии в group_key_history остаётся. Текущая версия группы
// не удаляется никогда: ею закрыты новые сообщения, и тихая группа без смены ключа иначе
// осталась бы без обёрток у тех, кто их ещё не забрал.
func (s *Store) GCGroupWrappedKeys(ctx context.Context, graceSec int64) (int64, error) {
	ct, err := s.pool.Exec(ctx, `
		DELETE FROM group_wrapped_keys w
		WHERE EXISTS (
			SELECT 1 FROM group_key_history n
			WHERE n.group_id = w.group_id AND n.gk_version > w.gk_version
			  AND n.rotated_at < now() - make_interval(secs => $1))`, graceSec)
	return ct.RowsAffected(), err
}

// GCExcludedGroupKeys удаляет wrapped_GK устройств участников, вышедших из
// группы раньше windowSec назад (окно апелляции, crypto-protocol §4.2).
func (s *Store) GCExcludedGroupKeys(ctx context.Context, windowSec int64) (int64, error) {
	ct, err := s.pool.Exec(ctx, `
		DELETE FROM group_wrapped_keys w
		USING devices d, memberships m
		WHERE d.device_id = w.recipient
		  AND m.target_type = 'group' AND m.target_id = w.group_id AND m.user_id = d.user_id
		  AND m.left_at IS NOT NULL AND m.left_at < now() - make_interval(secs => $1)`, windowSec)
	return ct.RowsAffected(), err
}

// GCExpiredSmsCodes удаляет коды, просроченные больше суток назад.
func (s *Store) GCExpiredSmsCodes(ctx context.Context) (int64, error) {
	ct, err := s.pool.Exec(ctx,
		`DELETE FROM sms_codes WHERE expires_at < now() - interval '24 hours'`)
	return ct.RowsAffected(), err
}

// GCExpiredLinkSessions удаляет сессии привязки устройства, просроченные больше
// суток назад. Живут они 5 минут, но сутки запаса дают возможность посмотреть в
// таблицу при разборе жалобы «привязка не сработала».
//
// Забранные (claimed) сессии тоже уходят: устройство уже заведено в devices,
// строка сессии дальше не нужна — а хранить в ней хэши секретов дольше
// необходимого незачем.
func (s *Store) GCExpiredLinkSessions(ctx context.Context) (int64, error) {
	ct, err := s.pool.Exec(ctx,
		`DELETE FROM device_link_sessions WHERE expires_at < now() - interval '24 hours'`)
	return ct.RowsAffected(), err
}
