-- Аватар сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ5): у групп столбец есть с 0005, у каналов и сообществ — новый.
-- Аватар публичен, как у человека: медиа по номеру отдаётся любому вошедшему; ставят владелец и админ.
ALTER TABLE channels    ADD COLUMN IF NOT EXISTS avatar_media_id UUID;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS avatar_media_id UUID;
