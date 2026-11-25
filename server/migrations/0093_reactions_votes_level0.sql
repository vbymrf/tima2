-- Лента канала без пузыря и «Описание» (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.18, СЦ37–СЦ40; заказчик 2026-11-25).
-- Всё аддитивно: новые таблицы и столбцы с умолчанием.

-- Эмоции — публичная оценка (§1.18, 1–3): одна на человека у записи канала и у сообщения уровня 0 («Описание»)
-- группы, канала, сообщества. Шкала «Оценки» — восемь эмоций и покой (doc/Layout-UI-light/телефон/подокна/оценка.html);
-- хранится ключ, а не знак: знак рисует клиент.
CREATE TABLE IF NOT EXISTS entity_reactions (
    kind       TEXT        NOT NULL CHECK (kind IN ('channel', 'group', 'community')),
    entity_id  UUID        NOT NULL,
    message_id BIGINT      NOT NULL,
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    emotion    TEXT        NOT NULL CHECK (emotion IN ('approval', 'contempt', 'laughter', 'pain', 'rage', 'fear',
                                                       'interest', 'boredom', 'calm')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, entity_id, message_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_entity_reactions_message ON entity_reactions (kind, entity_id, message_id);

-- Палец вверх и палец вниз — частная оценка записи канала (§1.18, 1–3): ею человек подбирает свою ленту, её не видит
-- никто, кроме него самого. Только у каналов.
CREATE TABLE IF NOT EXISTS channel_post_votes (
    channel_id UUID        NOT NULL,
    post_id    BIGINT      NOT NULL,
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    vote       SMALLINT    NOT NULL CHECK (vote IN (-1, 1)),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, post_id, user_id)
);

-- Комментарии к сообщениям уровня 0 («Описание») у групп и сообществ (§1.18, 11). У канала сообщения «Описания» —
-- записи уровня 0, и комментарии к ним — прежние комментарии записи канала.
CREATE TABLE IF NOT EXISTS level0_comments (
    comment_id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind               TEXT        NOT NULL CHECK (kind IN ('group', 'community')),
    entity_id          UUID        NOT NULL,
    message_id         BIGINT      NOT NULL,
    author_id          UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    text               TEXT        NOT NULL DEFAULT '',
    body               BYTEA,
    created_at_unix_ms BIGINT      NOT NULL,
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_level0_comments_message ON level0_comments (kind, entity_id, message_id, comment_id);

-- Настройки «Описания» (§1.18, 11) — «Управление → Основное»: показ целиком или обрезкой (по умолчанию обрезкой) и
-- комментарии к сообщениям уровня 0 (по умолчанию выключены).
ALTER TABLE groups      ADD COLUMN IF NOT EXISTS showcase_whole    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE channels    ADD COLUMN IF NOT EXISTS showcase_whole    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS showcase_whole    BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE groups      ADD COLUMN IF NOT EXISTS showcase_comments BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE channels    ADD COLUMN IF NOT EXISTS showcase_comments BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS showcase_comments BOOLEAN NOT NULL DEFAULT FALSE;
