-- 0007: синхронизация устройств (sync-offline.md §2; websocket-events.md).
-- Персистентный event log по устройствам: каждое доставляемое событие
-- (message.new, key.rotated, message.group, …) пишется сюда, live-доставка
-- через Redis Pub/Sub — лишь ускорение. Догон после офлайна — sync.pull
-- по cursor. Ретеншен 90 дней (событий и конвертов) — GC итерации worker-а.

CREATE TABLE IF NOT EXISTS device_events (
    event_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,  -- глобально монотонный
    device_id  UUID        NOT NULL,
    event_type TEXT        NOT NULL,
    payload    JSONB       NOT NULL,   -- поля кадра без event/event_id
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_device_events ON device_events (device_id, event_id);

-- Последний подтверждённый (ack) event_id устройства. Клиентский cursor —
-- первичен (локальная БД устройства); серверный — резерв на потерю клиентом.
CREATE TABLE IF NOT EXISTS sync_cursors (
    device_id  UUID   PRIMARY KEY,
    cursor     BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
