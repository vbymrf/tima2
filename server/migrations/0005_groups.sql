-- 0005: Group Service — группы (переписка) и подсистема membership (data-model.md §3).
-- memberships полиморфна: сюда же лягут роли channel/voice_room/community.
-- community_id пока без FK — таблица communities появится с модулем сообществ.

CREATE TABLE IF NOT EXISTS groups (
    group_id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    kind               TEXT        NOT NULL CHECK (kind IN ('private', 'public')),  -- private: E2E + GK
    title              TEXT        NOT NULL,
    description        TEXT,
    avatar_media_id    UUID,
    owner_id           UUID        NOT NULL REFERENCES users(user_id),
    community_id       UUID,                          -- 0..1 (communities.md §2)
    community_access   TEXT        DEFAULT 'open',    -- 'open'|'preview'|'restricted'
    restricted_visible BOOLEAN     DEFAULT TRUE,
    slow_mode_sec      INT,
    premoderation      BOOLEAN     DEFAULT FALSE,
    threads_only       BOOLEAN     DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at         TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS memberships (
    target_type  TEXT        NOT NULL,                -- 'group'|'channel'|'voice_room'|'community'
    target_id    UUID        NOT NULL,
    user_id      UUID        NOT NULL REFERENCES users(user_id),
    role         TEXT        NOT NULL DEFAULT 'member',
                 -- group: owner|admin|moderator|member (остальные сущности — data-model.md §3)
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at      TIMESTAMPTZ,                         -- выход/исключение; строка остаётся как история
    banned_until TIMESTAMPTZ,                         -- модерация: временная блокировка (писать нельзя)
    PRIMARY KEY (target_type, target_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_memberships_user ON memberships(user_id, target_type);
