-- 0074: правка сообщения — версия 3 конверта (ADR-0045; doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §8).
-- Правка — новая запись с номером исходного; 0 — обычное сообщение. Колонка с умолчанием:
-- строки до миграции остаются обычными сообщениями без единой ветки в коде.
ALTER TABLE personal_messages ADD COLUMN IF NOT EXISTS edit_of BIGINT NOT NULL DEFAULT 0;
ALTER TABLE group_messages    ADD COLUMN IF NOT EXISTS edit_of BIGINT NOT NULL DEFAULT 0;

-- Счёт правок исходного при приёме (не больше трёх): только по правкам, обычных строк в индексе нет.
CREATE INDEX IF NOT EXISTS idx_personal_messages_edits ON personal_messages (chat_id, edit_of) WHERE edit_of <> 0;
CREATE INDEX IF NOT EXISTS idx_group_messages_edits    ON group_messages (group_id, edit_of) WHERE edit_of <> 0;
