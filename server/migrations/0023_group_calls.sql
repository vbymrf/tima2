-- 0023: групповые звонки (ПЛАН-РЕФАКТОРИНГА.md Р5, doc_add/бриф по звонкам).
--
-- До этого `calls` описывала строго «один на один»: initiator_id + peer_id. Участник
-- как сущность отсутствовал, поэтому «кого пригласили» и «кто вправе войти» выразить
-- было нечем.
--
-- Что НЕ делаем: качеством медиа не занимаемся — это зона LiveKit (ADR-0006
-- Поправка-1). Здесь только сигналинг и состояние на нашей стороне: SFU про наши
-- звонки ничего не знает и знать не должен.

ALTER TABLE calls
    ADD COLUMN IF NOT EXISTS type TEXT NOT NULL DEFAULT 'direct'
        CHECK (type IN ('direct', 'group')),
    ADD COLUMN IF NOT EXISTS group_id UUID;

-- В групповом звонке «собеседника» нет — есть участники.
ALTER TABLE calls ALTER COLUMN peer_id DROP NOT NULL;

-- Кого пригласили и что с ним происходит.
--
-- Право на вход принадлежит ПРИГЛАШЁННОМУ и действует, пока звонок активен: и для
-- того, кто не ответил сразу, и для того, кто выпал и возвращается. LiveKit пускает
-- любого с валидным токеном — кому его выдать, решаем здесь.
CREATE TABLE IF NOT EXISTS call_participants (
    call_id    UUID        NOT NULL REFERENCES calls(call_id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL,
    -- invited — приглашён, ещё не входил
    -- joined  — сейчас в комнате (по вебхуку LiveKit)
    -- left    — вышел или выпал; может войти снова, пока звонок активен
    state      TEXT        NOT NULL DEFAULT 'invited'
        CHECK (state IN ('invited', 'joined', 'left')),
    invited_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    joined_at  TIMESTAMPTZ,
    left_at    TIMESTAMPTZ,
    PRIMARY KEY (call_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_call_participants_user ON call_participants(user_id, call_id);
