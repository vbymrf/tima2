-- 0008: состояние GC (worker) + индекс для ретеншена событий.
-- Правила ретеншена (sync-offline.md §1; escrow-legal-access.md §5):
--   device_events           > 90 дней  → удаляются (журнал доставки)
--   personal_message_keys   > 90 дней  → удаляются (история живёт на устройствах)
--   group_wrapped_keys      > 90 дней  → удаляются; исключённым — 30 дней после
--                                        выхода (окно апелляции, crypto-protocol §4.2)
--   sms_codes               просроченные > 24 ч → удаляются
-- Сами конверты (personal_messages, group_messages) НЕ удаляются, пока нет
-- escrow-архива: escrow_blob по спеке хранится до 7 лет (юридический доступ).

CREATE TABLE IF NOT EXISTS gc_state (
    key        TEXT   PRIMARY KEY,   -- 'events_watermark': max удалённый event_id (sync.gap)
    value      BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_device_events_created ON device_events(created_at);
