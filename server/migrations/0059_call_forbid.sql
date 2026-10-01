-- 0059: запрет создателя группового звонка (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ, решение 6, уточнено
-- заказчиком 2026-10-01): «выключить микрофон / видео» — это ЗАПРЕТ, а не выключение.
-- Сервер перестаёт принимать от участника этот источник, пока создатель не разрешит; при
-- перезаходе в звонок запрет действует — он выдаётся в правах токена.
--
-- Аддитивная: оба поля с умолчанием «не запрещено».
ALTER TABLE call_participants ADD COLUMN IF NOT EXISTS mic_forbidden BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE call_participants ADD COLUMN IF NOT EXISTS video_forbidden BOOLEAN NOT NULL DEFAULT false;
