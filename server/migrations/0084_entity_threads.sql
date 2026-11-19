-- Переписка «сущность — человек» (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.9, СЦ15).
--
-- Отдельный механизм: к сообщениям групп и каналов, уровням (ADR-0019), notify_settings и комментариям не привязан.
-- Одна лента на пару «сущность верхнего уровня — человек» (у элемента сообщества — лента сообщества). За сущность
-- пишет тот, у кого право сейчас; кто именно — сервер хранит (права, предел, разбор), человеку не отдаёт. Сквозного
-- шифрования нет: круг стороны «сущность» меняется. Содержимое — то же тело сообщения, что у личной группы, открытым.
CREATE TABLE IF NOT EXISTS entity_threads (
    thread_id     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_kind   TEXT        NOT NULL CHECK (entity_kind IN ('group', 'channel', 'community')),
    entity_id     UUID        NOT NULL,
    user_id       UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    -- Состояние у человека (§1.9, 10): on | off | blocked. blocked — сущность ему не пишет, сам он писать может.
    user_state    TEXT        NOT NULL DEFAULT 'on' CHECK (user_state IN ('on', 'off', 'blocked')),
    user_read     BIGINT      NOT NULL DEFAULT 0,
    -- Прочитано стороной сущности — общее для всей команды (§1.9, 6).
    entity_read   BIGINT      NOT NULL DEFAULT 0,
    -- Сущность заблокировала человека: он ей не пишет, лента остаётся.
    user_blocked  BOOLEAN     NOT NULL DEFAULT FALSE,
    last_id       BIGINT      NOT NULL DEFAULT 0,
    last_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    rev           BIGINT      NOT NULL DEFAULT 0,
    UNIQUE (entity_kind, entity_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_entity_threads_user   ON entity_threads (user_id, last_at DESC);
CREATE INDEX IF NOT EXISTS idx_entity_threads_entity ON entity_threads (entity_kind, entity_id, last_at DESC);

CREATE TABLE IF NOT EXISTS entity_thread_messages (
    thread_id  UUID        NOT NULL REFERENCES entity_threads (thread_id) ON DELETE CASCADE,
    message_id BIGSERIAL,
    side       TEXT        NOT NULL CHECK (side IN ('entity', 'user')),
    -- Кто писал за сущность — только серверу; человеку не отдаётся (§1.9, 2).
    author_id  UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    body       BYTEA       NOT NULL,
    preview    TEXT        NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (thread_id, message_id)
);
-- Предел «не больше пяти людей в сутки» считается по сообщениям стороны «сущность» за сутки.
CREATE INDEX IF NOT EXISTS idx_entity_thread_messages_side_time ON entity_thread_messages (side, created_at);

-- «Принимать сообщения» у сущности (§1.9, 3); по умолчанию — принимает.
ALTER TABLE groups      ADD COLUMN IF NOT EXISTS accept_messages BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE channels    ADD COLUMN IF NOT EXISTS accept_messages BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS accept_messages BOOLEAN NOT NULL DEFAULT TRUE;
