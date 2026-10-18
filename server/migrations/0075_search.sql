-- 0075: поиск по открытым уровням (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §12). Первый узел текста открытого
-- сообщения группы — его сервер берёт для поиска. У зашифрованного пусто: его ищут на устройстве.
--
-- search_key — тот же текст строчными, приведённый на сервере (Go), а не lower() базы: lower() и
-- ILIKE зависят от локали, и в базе с локалью C кириллица в нижний регистр не переводится вовсе.
-- Строки до миграции остаются пустыми — найдутся сообщения и записи, пришедшие после.
ALTER TABLE group_messages ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';
ALTER TABLE group_messages ADD COLUMN IF NOT EXISTS search_key  TEXT NOT NULL DEFAULT '';
ALTER TABLE channel_posts  ADD COLUMN IF NOT EXISTS search_key  TEXT NOT NULL DEFAULT '';
