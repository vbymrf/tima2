-- 0014: звонки (calls-livekit.md §3). Бэкенд ведёт комнату и историю; медиа идёт
-- через LiveKit (SFU/SRTP), не через нас. Состояния: ringing→answered→ended|missed.
CREATE TABLE IF NOT EXISTS calls (
    call_id      UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    room         TEXT        NOT NULL,                 -- имя комнаты LiveKit
    kind         TEXT        NOT NULL,                 -- audio|video
    initiator_id UUID        NOT NULL REFERENCES users(user_id),
    peer_id      UUID        NOT NULL REFERENCES users(user_id),  -- 1:1 (группы — позже)
    state        TEXT        NOT NULL DEFAULT 'ringing', -- ringing|answered|ended|missed
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    answered_at  TIMESTAMPTZ,
    ended_at     TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_calls_peer ON calls(peer_id, created_at DESC);
