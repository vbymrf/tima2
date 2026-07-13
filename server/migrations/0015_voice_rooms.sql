-- 0015: аудио-чаты (calls-livekit.md §3, communities.md). Постоянная голосовая
-- комната: участники входят/выходят свободно; комната LiveKit живёт всё время.
-- MVP: standalone (без обязательного community; привязка к сообществу — позже).
CREATE TABLE IF NOT EXISTS voice_rooms (
    room_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title      TEXT        NOT NULL,
    owner_id   UUID        NOT NULL REFERENCES users(user_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at  TIMESTAMPTZ
);
