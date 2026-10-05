-- 0065: копия ключей по модели Matrix (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ §3а, Р43–Р47, М1–М7).
--
-- Ключи сообщений по-прежнему заворачиваются на каждое устройство. К ним добавляется копия
-- под ОТКРЫТЫМ ключом копии личности: его пара выводится из фразы, открытая часть лежит здесь,
-- подписанная ключом личности — сервер не может подсунуть свою. Пополняют копию все свои
-- устройства без секрета; открывает её тот, у кого фраза (или ключ копии на телефоне, Р46).
--
-- Эпоха — номер пары. Отключение устройства (М5) переводит копию на новую пару, и обёртки
-- прежней эпохи перезаворачиваются; поэтому эпоха хранится у каждой обёртки.
CREATE TABLE IF NOT EXISTS key_copy (
    user_id    UUID        PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    epoch      INT         NOT NULL CHECK (epoch >= 1),
    pub        BYTEA       NOT NULL CHECK (octet_length(pub) = 32),
    -- Подпись ключом личности над `tima.key-copy.v1|<epoch>|<pub base64url>`.
    sig        BYTEA       NOT NULL CHECK (octet_length(sig) = 64),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Копия ключей личных сообщений — прежняя таблица этапа 4 ADR-0010. Клиент её не заполнял,
-- поэтому смысл обёртки задаётся заново: эфемерал (32) || обёртка под открытый ключ копии.
ALTER TABLE personal_message_backup ADD COLUMN IF NOT EXISTS epoch INT NOT NULL DEFAULT 0;

-- Копия ключей групп: каждая версия GK, которую держит устройство личности.
CREATE TABLE IF NOT EXISTS group_key_copy (
    owner_id   UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    group_id   UUID        NOT NULL,
    gk_version INT         NOT NULL,
    epoch      INT         NOT NULL,
    wrapped    BYTEA       NOT NULL,     -- эфемерал (32) || обёртка GK под открытый ключ копии
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, group_id, gk_version, epoch)
);
