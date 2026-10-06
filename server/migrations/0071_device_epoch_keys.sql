-- 0071: ключ шифрования устройства на эпоху (ПЛАН-(ПС) ПС3, Р2, Р3).
--
-- Раз в эпоху депозитария (календарный месяц UTC, «2026-10») устройство заводит новую пару
-- X25519 и публикует открытую часть, подписанную своим ключом подписи (он заверен КПУ или
-- ключом личности). Отправитель заворачивает под ключ эпохи, получатель держит прежние закрытые
-- ключи только на запас и уничтожает: утёкший потом ключ устройства не откроет прошлое.
-- В эпоху у устройства один ключ: подменить его нельзя.
CREATE TABLE IF NOT EXISTS device_epoch_keys (
    device_id      UUID        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    epoch          TEXT        NOT NULL CHECK (epoch ~ '^[0-9]{4}-[0-9]{2}$'),
    encryption_pub BYTEA       NOT NULL CHECK (octet_length(encryption_pub) = 32),
    signature      BYTEA       NOT NULL CHECK (octet_length(signature) = 64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, epoch)
);
