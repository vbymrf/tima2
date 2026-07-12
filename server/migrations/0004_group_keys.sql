-- 0004: групповые ключи (crypto-protocol.md §4; architecture §7.2).
-- GK генерирует клиент-инициатор; сервер хранит escrow версии и wrapped_GK
-- ПО УСТРОЙСТВАМ (crypto-protocol §4.1: Box(ephemeral, member_device_identity, GK)).
-- Сами группы (membership, роли) — модуль Group Service следующей итерации;
-- до него проверка «ротирующий — админ группы» не выполняется (TODO там же).

CREATE TABLE IF NOT EXISTS group_key_history (
    group_id             UUID        NOT NULL,
    gk_version           INT         NOT NULL,
    rotated_by           UUID        NOT NULL,   -- user_id инициатора
    sender_ephemeral_pub BYTEA       NOT NULL CHECK (octet_length(sender_ephemeral_pub) = 32),
    escrow_mlkem_ct      BYTEA       NOT NULL CHECK (octet_length(escrow_mlkem_ct) = 1088),
    escrow_wrapped_key   BYTEA       NOT NULL,
    escrow_key_version   INT         NOT NULL,
    reason               TEXT        NOT NULL DEFAULT 'periodic',  -- periodic|member_join|member_leave|compromise
    rotated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, gk_version)
);

CREATE TABLE IF NOT EXISTS group_wrapped_keys (
    group_id   UUID  NOT NULL,
    gk_version INT   NOT NULL,
    recipient  UUID  NOT NULL,   -- device_id (или vu_id)
    wrapped    BYTEA NOT NULL,
    PRIMARY KEY (group_id, gk_version, recipient),
    FOREIGN KEY (group_id, gk_version) REFERENCES group_key_history(group_id, gk_version) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_group_wrapped_recipient ON group_wrapped_keys(recipient, group_id, gk_version);
