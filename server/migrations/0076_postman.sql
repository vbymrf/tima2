-- 0076: почтальон — файлы вне пузыря в личной переписке (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §6, ПЛАН-(ПФ) ПФ4).
--
-- Сервер держит файл, пока его не заберут: первое скачивание получателем в течение часа после
-- загрузки — файл живёт до конца часа; позже — удаляется сразу после этого скачивания; никто не
-- скачал — через неделю. Шестимесячный пол на файлы не распространяется.
--
-- postman_chat пусто — обычное медиа (аватар, картинка внутри сообщения), правила почтальона его
-- не касаются. status: pending → complete → gone (удалён по сроку, строка остаётся: по ней
-- просят повторную загрузку).
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS postman_chat   UUID;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS postman_peer   UUID;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS owner_device   UUID;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS uploaded_at    TIMESTAMPTZ;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS first_fetch_at TIMESTAMPTZ;
ALTER TABLE media_objects ADD COLUMN IF NOT EXISTS expires_at     TIMESTAMPTZ;

-- Уборщик раз в минуту: только живые файлы почтальона со сроком.
CREATE INDEX IF NOT EXISTS idx_media_postman_expiry ON media_objects (expires_at)
    WHERE postman_chat IS NOT NULL AND status = 'complete';
