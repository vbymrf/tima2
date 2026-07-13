-- 0008: восстановление истории группы у участников (ADR-0010, этап 1).
-- Обёртка восстановления кладётся в ту же group_wrapped_keys, но её эфемерный
-- ключ — от УСТРОЙСТВА-ПОМОЩНИКА, а не от исходной ротации (в group_key_history).
-- Поэтому храним sender_ephemeral_pub рядом с обёрткой; NULL = обычная обёртка
-- ротации (используется общий из group_key_history).
ALTER TABLE group_wrapped_keys
    ADD COLUMN IF NOT EXISTS sender_ephemeral_pub BYTEA
        CHECK (sender_ephemeral_pub IS NULL OR octet_length(sender_ephemeral_pub) = 32);
