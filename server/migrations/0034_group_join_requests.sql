-- 0034: заявка на вступление (ADR-0018 п. 7, ПЛАН-СОЦИУМА Г3).
--
-- Понятия «попроситься» в сервере не было вовсе, а для личной группы это ЕДИНСТВЕННОЕ
-- доступное постороннему действие: она не ищется, вступить самому некуда, и всё, что
-- человек может, — попросить админа.
--
-- ОТКАЗ ВИДЕН ПРОСИВШЕМУ (решение заказчика 2026-09-04). Поэтому у заявки есть состояние
-- 'declined', а не «строка исчезает». Молчаливый отказ неотличим от «не дошло» — человек
-- попросит снова, и админ получит ту же заявку второй и третий раз.
--
-- ОДНА СТРОКА НА ПАРУ, А НЕ ЖУРНАЛ. Первичный ключ (group_id, user_id): повторная
-- просьба обновляет существующую строку, а не плодит новые. История «просил трижды»
-- никому не нужна, а очередь админа от неё замусорилась бы.

CREATE TABLE IF NOT EXISTS group_join_requests (
    group_id    UUID        NOT NULL REFERENCES groups(group_id) ON DELETE CASCADE,
    user_id     UUID        NOT NULL REFERENCES users(user_id)   ON DELETE CASCADE,
    state       TEXT        NOT NULL DEFAULT 'pending'
                            CHECK (state IN ('pending', 'accepted', 'declined')),
    asked_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at TIMESTAMPTZ,
    answered_by UUID,
    PRIMARY KEY (group_id, user_id)
);

-- Очередь админа: «кто просит в эту группу» — первый экран Г9.
CREATE INDEX IF NOT EXISTS idx_gjr_pending
    ON group_join_requests (group_id, asked_at DESC) WHERE state = 'pending';
