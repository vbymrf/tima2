-- Звуковой чат в сообществе (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ7).
--
-- Только внутри сообщества; создают владелец и админы. Говорят все, кто вошёл (§1.5, 2) — деление на говорящих и
-- слушателей у звукового чата сообщества не используется. Настройки: можно ли участникам выключать микрофон и звук,
-- разрешён ли ведущий, может ли ведущий выгонять, наибольшее число участников (сервер проверяет на входе).
-- Текст чата — группа, связанная с комнатой (group_id); звук без сквозного шифрования, записи нет.
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS community_id     UUID REFERENCES communities (community_id) ON DELETE CASCADE;
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS group_id         UUID;
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS allow_mute       BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS host_allowed     BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS host_can_kick    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS max_participants INTEGER NOT NULL DEFAULT 50 CHECK (max_participants BETWEEN 2 AND 1000);
ALTER TABLE voice_rooms ADD COLUMN IF NOT EXISTS host_id          UUID REFERENCES users (user_id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_voice_rooms_community ON voice_rooms (community_id) WHERE closed_at IS NULL;

-- Кто сейчас внутри: предел участников и ведущий — среди вошедших. Строка уходит при выходе и выгоне.
CREATE TABLE IF NOT EXISTS voice_room_present (
    room_id   UUID        NOT NULL REFERENCES voice_rooms (room_id) ON DELETE CASCADE,
    user_id   UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);

-- Выгнанный не входит до конца разговора: строка живёт, пока в комнате кто-то есть (уборка — при опустевшей комнате).
CREATE TABLE IF NOT EXISTS voice_room_kicked (
    room_id   UUID        NOT NULL REFERENCES voice_rooms (room_id) ON DELETE CASCADE,
    user_id   UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    kicked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);
