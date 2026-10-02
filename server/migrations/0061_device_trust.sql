-- 0061: доверие к устройствам (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ1–ДУ2, беда «вор SIM читает новые
-- сообщения»).
--
-- Ключ подписи устройств (КПУ) — промежуточный ключ аккаунта: фраза (ключ личности) подписывает
-- его, он подписывает устройства. По одному на телефон: закрытый ключ рождается на телефоне и
-- никуда не переезжает. Отозванный КПУ (кража телефона, отзыв устройства) перестаёт заверять
-- всё, что им подписано.
CREATE TABLE IF NOT EXISTS account_signing_keys (
    ask_id     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    -- Телефон, на котором лежит закрытый ключ. Отзыв телефона отзывает и КПУ.
    device_id  UUID        REFERENCES devices(device_id) ON DELETE SET NULL,
    ask_pub    BYTEA       NOT NULL,
    -- Подпись ключом личности над `tima.ask.v1|<ask_pub>`.
    ask_sig    BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_account_signing_keys_live
    ON account_signing_keys (user_id) WHERE revoked_at IS NULL;

-- Свидетельство устройства: кем подписаны его открытые ключи. Пусто — устройство заведено до
-- ДУ1 или без доказательства, в строгом режиме собеседники ему не шифруют.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS cert_by     TEXT NOT NULL DEFAULT '';  -- '' | identity | ask
ALTER TABLE devices ADD COLUMN IF NOT EXISTS cert_ask_id UUID REFERENCES account_signing_keys(ask_id);
ALTER TABLE devices ADD COLUMN IF NOT EXISTS cert_sig    BYTEA;
