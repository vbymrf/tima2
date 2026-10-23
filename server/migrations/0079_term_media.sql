-- 0079: файлы на срок — голосовые сообщения (ПЛАН-(ВМ) Р3, Р20).
--
-- Сервер держит файл срок, заданный на сервере (TIMA_MEDIA_TERM_DAYS, по умолчанию неделя), от
-- загрузки; кто скачал — не отслеживается. Одно правило для личной переписки и группы: личная —
-- term_chat и term_peer, группа — term_group. Отдаётся участникам. Срок вышел — строка gone,
-- повторной загрузки нет.
--
-- term_sec = 0 — не файл на срок (аватар, картинка, почтальон).
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS term_sec   INT NOT NULL DEFAULT 0;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS term_chat  UUID;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS term_peer  UUID;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS term_group UUID;

-- Уборщик раз в минуту: только живые файлы на срок.
CREATE INDEX IF NOT EXISTS idx_media_term_expiry ON media_objects (expires_at)
    WHERE term_sec > 0 AND status = 'complete';
