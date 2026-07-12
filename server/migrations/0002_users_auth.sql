-- 0002: пользователи и SMS-коды (фаза Auth, MVP).
-- Полный цикл auth (guest, recovery, link, attestation) — api-overview.md §Auth; здесь ядро.

CREATE TABLE IF NOT EXISTS users (
    user_id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    phone        TEXT        NOT NULL UNIQUE,          -- E.164
    display_name TEXT        NOT NULL DEFAULT '',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Одноразовые коды входа. Хранится только hash: утечка таблицы не раскрывает коды.
CREATE TABLE IF NOT EXISTS sms_codes (
    request_id UUID        PRIMARY KEY,
    phone      TEXT        NOT NULL,
    code_hash  BYTEA       NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sms_codes_phone ON sms_codes(phone, created_at DESC);

-- device_id теперь назначает сервер при регистрации
ALTER TABLE devices ALTER COLUMN device_id SET DEFAULT gen_random_uuid();
