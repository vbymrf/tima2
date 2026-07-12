-- 0001: личные сообщения — раскладка Envelope (envelope.proto → таблицы).
-- Сервер хранит только ciphertext и обёртки; расшифровать не может (crypto-protocol.md §10).

-- Устройства: публичные ключи для проверки подписи при приёме (§7) и адресации wrapped keys.
CREATE TABLE IF NOT EXISTS devices (
    device_id       UUID PRIMARY KEY,
    user_id         UUID        NOT NULL,
    encryption_pub  BYTEA       NOT NULL CHECK (octet_length(encryption_pub) = 32),  -- X25519
    signing_pub     BYTEA       NOT NULL CHECK (octet_length(signing_pub) = 32),     -- Ed25519
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_devices_user ON devices(user_id);

-- Конверты: meta plaintext + ciphertext-блобы как есть.
CREATE TABLE IF NOT EXISTS personal_messages (
    chat_id             UUID        NOT NULL,
    message_id          BIGINT      NOT NULL,               -- назначает клиент (входит в подпись)
    client_msg_id       UUID        NOT NULL,               -- дедупликация POST /messages
    sender_id           UUID        NOT NULL,
    sender_device       UUID        NOT NULL,
    kind                INT         NOT NULL,
    created_at_unix_ms  BIGINT      NOT NULL,
    reply_to            BIGINT      NOT NULL DEFAULT 0,
    format_version      INT         NOT NULL,
    encrypted_payload   BYTEA       NOT NULL,
    escrow_mlkem_ct     BYTEA       NOT NULL CHECK (octet_length(escrow_mlkem_ct) = 1088),
    escrow_wrapped_key  BYTEA       NOT NULL,
    escrow_key_version  INT         NOT NULL,
    sender_ephemeral_pub BYTEA      NOT NULL CHECK (octet_length(sender_ephemeral_pub) = 32),
    ratchet_envelope    BYTEA,                              -- NULL, пока ratchet не включён (фаза 5)
    signature           BYTEA       NOT NULL CHECK (octet_length(signature) = 64),
    received_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted             BOOLEAN     NOT NULL DEFAULT FALSE, -- soft delete: клиентам не отдаётся
    deleted_at          TIMESTAMPTZ,
    deleted_by          UUID,
    PRIMARY KEY (chat_id, message_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_dedup ON personal_messages(chat_id, sender_device, client_msg_id);
CREATE INDEX IF NOT EXISTS idx_messages_chat_recent ON personal_messages(chat_id, message_id DESC);

-- Обёртки ключа сообщения (план Б): по строке на устройство получателя И отправителя.
CREATE TABLE IF NOT EXISTS personal_message_keys (
    chat_id     UUID   NOT NULL,
    message_id  BIGINT NOT NULL,
    recipient   UUID   NOT NULL,                            -- device_id (или vu_id)
    wrapped     BYTEA  NOT NULL,
    PRIMARY KEY (chat_id, message_id, recipient),
    FOREIGN KEY (chat_id, message_id) REFERENCES personal_messages(chat_id, message_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_message_keys_recipient ON personal_message_keys(recipient);
