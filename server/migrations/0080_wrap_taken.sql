-- Обёртки личных сообщений живут до доставки (план КК, КК10; модель Р5, Р8 — только личные).
--
-- taken_at — когда устройство-получатель развернуло ключ сообщения и сказало об этом. Забранная обёртка
-- стирается через запас (TIMA_WRAP_GRACE_DAYS); не забранная живёт, пока лежит содержимое сообщения;
-- обёртки отключённого устройства стираются сразу. Миграция аддитивная: NULL — не забрано.
ALTER TABLE personal_message_keys ADD COLUMN IF NOT EXISTS taken_at TIMESTAMPTZ;

-- Счёт не забранных у устройства — по нему устройство решает, можно ли уничтожить ключ прошлой эпохи.
CREATE INDEX IF NOT EXISTS idx_message_keys_untaken ON personal_message_keys(recipient) WHERE taken_at IS NULL;
