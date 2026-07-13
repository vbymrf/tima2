-- 0013: публичные каналы (data-model.md §3; communities.md). Канал — односторонняя
-- трансляция: владелец/админы публикуют посты, подписчики читают. Контент публичный
-- (не E2E) — это осознанно для вещания (в отличие от private-групп с GK).
CREATE TABLE IF NOT EXISTS channels (
    channel_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title       TEXT        NOT NULL,
    description TEXT        NOT NULL DEFAULT '',
    owner_id    UUID        NOT NULL REFERENCES users(user_id),
    is_public   BOOLEAN     NOT NULL DEFAULT TRUE,  -- виден в каталоге и без подписки
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ
);

-- Подписчики канала (subscriptions в data-model — полиморфные; для MVP отдельная таблица).
CREATE TABLE IF NOT EXISTS channel_subscriptions (
    channel_id    UUID        NOT NULL,
    subscriber_id UUID        NOT NULL REFERENCES users(user_id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (channel_id, subscriber_id)
);
CREATE INDEX IF NOT EXISTS idx_channel_subs_user ON channel_subscriptions(subscriber_id);

-- Посты канала (публикации): plaintext-текст, id назначает база.
CREATE TABLE IF NOT EXISTS channel_posts (
    channel_id         UUID        NOT NULL REFERENCES channels(channel_id),
    post_id            BIGINT      GENERATED ALWAYS AS IDENTITY,
    author_id          UUID        NOT NULL,
    text               TEXT        NOT NULL,
    created_at_unix_ms BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (channel_id, post_id)
);
CREATE INDEX IF NOT EXISTS idx_channel_posts_recent ON channel_posts(channel_id, post_id DESC);
