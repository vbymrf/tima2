-- Удаление сущности с месяцем на отмену (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ14).
--
-- Как `users.delete_at` у удаления личности (0067, Р37, Р39): «удалить» ставит срок через 30 дней, сущность живёт как
-- прежде, владелец может отменить; по сроку работник делает то, что прежде делало мгновенное удаление (`deleted_at`).
ALTER TABLE groups      ADD COLUMN IF NOT EXISTS delete_at TIMESTAMPTZ;
ALTER TABLE channels    ADD COLUMN IF NOT EXISTS delete_at TIMESTAMPTZ;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS delete_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_groups_delete_due      ON groups (delete_at)      WHERE deleted_at IS NULL AND delete_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_channels_delete_due    ON channels (delete_at)    WHERE deleted_at IS NULL AND delete_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_communities_delete_due ON communities (delete_at) WHERE deleted_at IS NULL AND delete_at IS NOT NULL;
