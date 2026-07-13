-- 0016: роли в аудио-чатах (calls-livekit.md §3: спикеры/слушатели в токене).
-- Владелец — всегда спикер (неявно); остальным слово выдаёт владелец. Строка здесь =
-- пользователю выдано право говорить (canPublish). Нет строки → слушатель.
CREATE TABLE IF NOT EXISTS voice_speakers (
    room_id    UUID        NOT NULL,
    user_id    UUID        NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, user_id)
);
