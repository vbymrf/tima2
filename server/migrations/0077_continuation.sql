-- Пометка «только продолжение» (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §11.3, ПЛАН-(РС) РС3): закрытая часть
-- сообщения — отдельное сообщение своего уровня, подложенное в конец открытого. Само по себе в
-- ленте не показывается — только через подкладку и прямой переход. Пометка открытая (её видит
-- сервер), поэтому колонка, а не поле в зашифрованном теле (ADR-0011 §5).
--
-- Добавляющая: умолчание false — прежние записи остаются обычными.
ALTER TABLE group_messages ADD COLUMN IF NOT EXISTS only_continuation BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE channel_posts  ADD COLUMN IF NOT EXISTS only_continuation BOOLEAN NOT NULL DEFAULT false;
