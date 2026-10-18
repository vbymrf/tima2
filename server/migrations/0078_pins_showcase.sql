-- Закреплённое сообщение и описание (doc_mig/ПЛАН-(ЗО)-ЗАКРЕПЛЕНИЯ-И-ОПИСАНИЯ.md, ЗО1–ЗО2).
--
-- Закрепление — одно на переписку, группу, канал; сервер знает, что закреплено (заказчик
-- 2026-10-18: «иначе никто не узнает; метаданные у нас открытые»). Личной переписки таблицей нет —
-- номер выводится, поэтому контейнер называется видом и номером.
CREATE TABLE IF NOT EXISTS pins (
    container_kind TEXT        NOT NULL CHECK (container_kind IN ('personal', 'group', 'channel')),
    container_id   TEXT        NOT NULL,
    message_id     BIGINT      NOT NULL,
    pinned_by      UUID        NOT NULL,
    pinned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (container_kind, container_id)
);

-- Суперсообщение описания: место есть у каждой группы, канала, сообщества — по умолчанию пустое.
-- Ставит владелец; само суперсообщение — обычное сообщение уровня 0 этого контейнера.
ALTER TABLE groups      ADD COLUMN IF NOT EXISTS showcase_super BIGINT;
ALTER TABLE channels    ADD COLUMN IF NOT EXISTS showcase_super BIGINT;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS showcase_super BIGINT;
