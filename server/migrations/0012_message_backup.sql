-- 0012: резервная копия ключей «сообщений себе» под фразу (ADR-0010 §этап 4).
-- У self-чата (заметки) нет живых источников для peer-восстановления — единственный
-- путь возврата на новом устройстве. Обёртка = SecretBox(message_key, backup_key),
-- где backup_key выведен из recovery-фразы владельца. Сервер видит только шифртекст.
CREATE TABLE IF NOT EXISTS personal_message_backup (
    chat_id    UUID   NOT NULL,
    message_id BIGINT NOT NULL,
    owner_id   UUID   NOT NULL,               -- чей бэкап (по backup_key владельца)
    wrapped    BYTEA  NOT NULL,               -- SecretBox(message_key, backup_key)
    PRIMARY KEY (chat_id, message_id, owner_id),
    FOREIGN KEY (chat_id, message_id) REFERENCES personal_messages(chat_id, message_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_message_backup_owner ON personal_message_backup(owner_id, chat_id);
