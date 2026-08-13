-- 0027: привязка нового устройства по QR (key-lifecycle.md §2; timaNC/server/internal/phase1
-- дало исходную идею протокола, схема здесь адаптирована под текущую devices-таблицу —
-- у нас устройство не помечается платформой и не заворачивает отдельный секрет, поэтому
-- полей меньше, чем в прототипе).
--
-- Роли: НОВОЕ устройство (аккаунта ещё нет) вызывает /link/start и показывает QR;
-- уже авторизованное доверенное устройство сканирует его и вызывает /link/confirm —
-- одним запросом добавляет владельца QR как своё новое устройство. Секреты (QR-payload,
-- claim-токен) хранятся только хэшами, как sms-код и registration-токен.
CREATE TABLE IF NOT EXISTS device_link_sessions (
    session_id       UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    encryption_pub   BYTEA       NOT NULL CHECK (octet_length(encryption_pub) = 32),
    signing_pub      BYTEA       NOT NULL CHECK (octet_length(signing_pub) = 32),
    device_name      TEXT        NOT NULL CHECK (char_length(device_name) BETWEEN 1 AND 100),
    secret_hash      BYTEA       NOT NULL CHECK (octet_length(secret_hash) = 32),
    claim_token_hash BYTEA       NOT NULL CHECK (octet_length(claim_token_hash) = 32),
    user_id          UUID,
    linked_device_id UUID REFERENCES devices(device_id),
    expires_at       TIMESTAMPTZ NOT NULL,
    confirmed_at     TIMESTAMPTZ,
    claimed_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT device_link_confirmation_check CHECK (
        (confirmed_at IS NULL AND user_id IS NULL AND linked_device_id IS NULL)
        OR
        (confirmed_at IS NOT NULL AND user_id IS NOT NULL AND linked_device_id IS NOT NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_device_link_claim_token ON device_link_sessions(claim_token_hash);
CREATE INDEX IF NOT EXISTS idx_device_link_expiry ON device_link_sessions(expires_at);
