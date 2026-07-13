-- 0011: восстановление истории личного чата у собеседника/своего устройства
-- (ADR-0010, этап 2). Обёртка восстановления ключа сообщения кладётся в ту же
-- personal_message_keys, но её эфемерал — от УСТРОЙСТВА-ПОМОЩНИКА (перезаворачивает
-- message_key под новое устройство), а не оригинального отправителя (в personal_messages).
-- NULL = обычная обёртка (используется sender_ephemeral_pub из personal_messages).
ALTER TABLE personal_message_keys
    ADD COLUMN IF NOT EXISTS sender_ephemeral_pub BYTEA
        CHECK (sender_ephemeral_pub IS NULL OR octet_length(sender_ephemeral_pub) = 32);
