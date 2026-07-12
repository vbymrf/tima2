-- 0006: сообщения групп (data-model.md §4; crypto-protocol.md §4.1).
-- Отличия от эскиза data-model: + sender_device и created_at_unix_ms (входят в
-- подпись — без них получатель не проверит её), + client_msg_id (дедуп повторной
-- отправки, как в personal_messages), kind — INT (ContentKind из envelope.proto).
-- sender_type/via_bot/forward_* придут с ботами; премодерация (pending) — когда
-- data-model §4 получит колонку статуса; партиционирование по created_at — при
-- масштабе (эксплуатационная миграция).

CREATE TABLE IF NOT EXISTS group_messages (
    message_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id           UUID   NOT NULL,
    client_msg_id      UUID   NOT NULL,
    sender_id          UUID   NOT NULL,
    sender_device      UUID   NOT NULL,
    kind               INT    NOT NULL DEFAULT 0,
    gk_version         INT,                 -- NULL: публичная группа (plaintext)
    payload            BYTEA  NOT NULL,     -- private: SecretBox(zstd(MessageBody), GK); public: protobuf MessageBody
    thread_root        BIGINT,              -- ветки: message_id корня
    reply_to           BIGINT,
    created_at_unix_ms BIGINT NOT NULL,     -- клиентское время (входит в подпись)
    signature          BYTEA  NOT NULL CHECK (octet_length(signature) = 64),  -- Ed25519 устройства
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted            BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at         TIMESTAMPTZ,
    deleted_by         UUID,
    UNIQUE (group_id, client_msg_id)
);
CREATE INDEX IF NOT EXISTS idx_gm_group ON group_messages (group_id, message_id DESC);
CREATE INDEX IF NOT EXISTS idx_gm_thread ON group_messages (group_id, thread_root) WHERE thread_root IS NOT NULL;
-- slow mode: последнее сообщение отправителя в группе
CREATE INDEX IF NOT EXISTS idx_gm_sender ON group_messages (group_id, sender_id, created_at DESC);
