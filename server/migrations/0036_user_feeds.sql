-- 0036: лента пользователя и перенос ссылкой (ADR-0019 §7, ПЛАН-СОЦИУМА Г8).
--
-- ЛЕНТА — ЭТО КАНАЛ (решение заказчика 2026-09-04). Не новая подсистема с собственными
-- правами и выдачей, а канал, который ищут по человеку: односторонняя трансляция, где
-- пишет владелец, а остальные читают. Оттого достаются готовыми посты, подписчики,
-- удаление и уведомления.
--
-- ПОДПИСКА АВТОМАТИЧЕСКАЯ. Кнопки «подписаться на ленту» нет: подписчиком становится тот,
-- кто ленту открыл. Друзей на сервере нет и графа знакомств не будет (решение А3),
-- поэтому «свои» пока не отличаются от посторонних — и уровень 2 на чужой странице не
-- показывается никому, кроме владельца. Когда друзья появятся, поменяется граница выдачи,
-- а не устройство ленты.
CREATE TABLE IF NOT EXISTS user_feeds (
    user_id    UUID        PRIMARY KEY REFERENCES users(user_id)       ON DELETE CASCADE,
    channel_id UUID        NOT NULL UNIQUE REFERENCES channels(channel_id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- КРУГ У ПОСТА. Тот же порядок, что у сообщений групп (ADR-0019): 0 всем и всегда, 1
-- всем, 2 своим, 3 по разрешению. Шифра здесь нет вовсе — канал открытая трансляция, —
-- поэтому −1 в канале не бывает, и умолчание «всем».
ALTER TABLE channel_posts
    ADD COLUMN IF NOT EXISTS level SMALLINT NOT NULL DEFAULT 1;

-- ПЕРЕНОС — ССЫЛКА, А НЕ КОПИЯ (ADR-0019 §7). Пост-ссылка не несёт содержимого: авторство,
-- текст и подпись берутся из оригинала при выдаче. Отсюда три свойства, ради которых всё
-- и сделано: автор остаётся автором, удаление оригинала убирает принесённое везде,
-- сужение круга действует везде. Копия не умеет ничего из этого.
--
-- Обе колонки или ни одной: половина ссылки — это запись, ссылающаяся в никуда.
ALTER TABLE channel_posts
    ADD COLUMN IF NOT EXISTS ref_group_id   UUID,
    ADD COLUMN IF NOT EXISTS ref_message_id BIGINT;

ALTER TABLE channel_posts DROP CONSTRAINT IF EXISTS chk_cp_ref_pair;
ALTER TABLE channel_posts ADD CONSTRAINT chk_cp_ref_pair CHECK (
    (ref_group_id IS NULL AND ref_message_id IS NULL)
    OR (ref_group_id IS NOT NULL AND ref_message_id IS NOT NULL)
);
ALTER TABLE channel_posts DROP CONSTRAINT IF EXISTS chk_cp_level;
ALTER TABLE channel_posts ADD CONSTRAINT chk_cp_level CHECK (level BETWEEN 0 AND 3);

-- Одна и та же запись не приносится к себе дважды: вторая ссылка ничего не добавляет, а
-- в ленте выглядит повтором.
CREATE UNIQUE INDEX IF NOT EXISTS idx_cp_ref_once
    ON channel_posts (channel_id, ref_group_id, ref_message_id)
    WHERE ref_group_id IS NOT NULL AND NOT deleted;
