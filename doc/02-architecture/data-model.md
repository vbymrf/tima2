# Модель данных (PostgreSQL)

> Каноническая схема. Крипто-таблицы согласованы с [crypto-protocol.md](../03-security/crypto-protocol.md). Принципы: append-only для сообщений, soft delete, только ciphertext для защищённого контента, бинарные данные — в MinIO ([ADR-0003](../adr/0003-postgresql-storage.md)).

## 1. Идентификация и устройства

```sql
CREATE TABLE users (
    user_id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_type    TEXT NOT NULL DEFAULT 'full',  -- 'full' | 'temporary' | 'virtual' (§10)
    owner_user_id   UUID REFERENCES users,         -- только для virtual: владелец (известен лишь серверу)
    phone           TEXT UNIQUE,                   -- E.164; NULL для temporary/virtual
    email           TEXT UNIQUE,                   -- обязателен для full (проверка на уровне приложения)
    username        TEXT UNIQUE,                   -- @упоминания
    display_name    TEXT NOT NULL,
    avatar_media_id UUID,
    bio             TEXT,
    invisible_mode  BOOLEAN DEFAULT FALSE,
    last_active_at  TIMESTAMPTZ,                   -- temporary: удаление после 30 дней неактивности
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ                    -- удаление с задержкой 30 дней
);
-- Временный аккаунт: одно устройство, нет recovery и мультиустройства;
-- апгрейд до full = привязка phone+email (key-lifecycle.md §7)

CREATE TABLE devices (
    device_id       UUID PRIMARY KEY,
    user_id         UUID NOT NULL REFERENCES users,
    platform        TEXT NOT NULL,                 -- 'android'|'ios'|'windows'|'web' (web — в планах)
    identity_pub    BYTEA NOT NULL,                -- Curve25519 public
    signing_pub     BYTEA NOT NULL,                -- Ed25519 public
    is_trust_anchor BOOLEAN DEFAULT FALSE,         -- телефон-якорь
    attested_at     TIMESTAMPTZ,                   -- прошёл аттестацию
    push_token      TEXT,
    last_seen_at    TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ
);

CREATE TABLE prekeys (                             -- X3DH bundles (фаза ratchet)
    device_id       UUID REFERENCES devices,
    key_id          INT NOT NULL,
    kind            TEXT NOT NULL,                 -- 'signed'|'onetime'
    public_key      BYTEA NOT NULL,
    signature       BYTEA,                         -- для signed prekey
    consumed_at     TIMESTAMPTZ,
    PRIMARY KEY (device_id, kind, key_id)
);

CREATE TABLE sessions (
    session_id      UUID PRIMARY KEY,
    device_id       UUID NOT NULL REFERENCES devices,
    refresh_hash    BYTEA NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 2. Социальный граф

```sql
CREATE TABLE contacts (                            -- телефонная книга (именная)
    owner_id        UUID REFERENCES users,
    contact_user_id UUID REFERENCES users,
    custom_name     TEXT,
    PRIMARY KEY (owner_id, contact_user_id)
);

CREATE TABLE friend_requests (
    from_user       UUID REFERENCES users,
    to_user         UUID REFERENCES users,
    status          TEXT NOT NULL DEFAULT 'pending',  -- pending|accepted|declined
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (from_user, to_user)
);

CREATE TABLE subscriptions (                       -- подписки; сообщество — единица подписки
    subscriber_id   UUID REFERENCES users,
    target_type     TEXT NOT NULL,                 -- 'user'|'group'|'channel'|'community'
    target_id       UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (subscriber_id, target_type, target_id)
);
-- Подписка на community раскрывается в контент его open/preview-элементов (fan-out);
-- прямые подписки на standalone группы/каналы работают как раньше (communities.md §3)

CREATE TABLE blocklist (
    owner_id        UUID REFERENCES users,
    blocked_id      UUID REFERENCES users,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, blocked_id)
);
```

## 3. Чаты, группы, каналы, аудио-чаты, сообщества

Сущности **разделены** по функции — свои таблицы у каждой; общее (роли, инвайты, папки) — полиморфные подсистемы. Границы модулей: [module-boundaries.md](./module-boundaries.md), доменная модель: [communities.md](../01-product/communities.md).

```
communities ◄─ community_id ─┬─ groups       (переписка: group_messages, GK)
     ▲                       ├─ channels     (публикации: posts author_type='channel')
     │ (0..1; voice: 1)      └─ voice_rooms  (live-аудио; community_id NOT NULL,
     │                                        attached_type/id → слабая ссылка на group|channel)
users ──< memberships (target: group|channel|voice_room|community; роли)
users ──< subscriptions (target: user|group|channel|community)      подписки
users ──< chat_folders ──< chat_folder_items (target: chat|group|channel|community)  папки
invites (target: group|channel|community)                            инвайты
groups(kind='private') ──< group_key_history ──< user_wrapped_keys   крипто (§5)
```

```sql
CREATE TABLE chats (                               -- личные 1:1
    chat_id         UUID PRIMARY KEY,
    user_a          UUID NOT NULL REFERENCES users,
    user_b          UUID NOT NULL REFERENCES users,
    ttl_seconds     INT,                           -- самоуничтожение (временный чат)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_a, user_b)
);

CREATE TABLE communities (                         -- административный контейнер
    community_id    UUID PRIMARY KEY,
    title           TEXT NOT NULL,
    description     TEXT,
    avatar_media_id UUID,
    owner_id        UUID NOT NULL REFERENCES users,
    auto_created    BOOLEAN DEFAULT FALSE,         -- создано автоматически при создании аудио-чата
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ                    -- удаление: элементы становятся standalone
);

-- === Модуль group: переписка ===
CREATE TABLE groups (
    group_id        UUID PRIMARY KEY,
    kind            TEXT NOT NULL,                 -- 'private' (E2E, GK) | 'public'
    title           TEXT NOT NULL,
    description     TEXT,
    avatar_media_id UUID,
    owner_id        UUID NOT NULL REFERENCES users,
    community_id    UUID REFERENCES communities,   -- 0..1 (communities.md §2)
    community_access TEXT DEFAULT 'open',          -- 'open'|'preview'|'restricted'
    restricted_visible BOOLEAN DEFAULT TRUE,
    slow_mode_sec   INT,                           -- задержка между сообщениями участника
    premoderation   BOOLEAN DEFAULT FALSE,         -- премодерация сообщений (публичные группы)
    threads_only    BOOLEAN DEFAULT FALSE,         -- показывать только сообщения с ветками
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

-- === Модуль channel: публикации ===
-- Контент канала — ТОЛЬКО посты (§7, author_type='channel'); чат-потока у канала нет.
CREATE TABLE channels (
    channel_id      UUID PRIMARY KEY,
    title           TEXT NOT NULL,
    description     TEXT,
    avatar_media_id UUID,
    owner_id        UUID NOT NULL REFERENCES users,
    community_id    UUID REFERENCES communities,
    community_access TEXT DEFAULT 'open',
    restricted_visible BOOLEAN DEFAULT TRUE,
    who_can_post    TEXT DEFAULT 'admins',         -- 'admins'|'admins_authors'
    premoderation   BOOLEAN DEFAULT FALSE,         -- посты авторов → status='pending'
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

-- === Модуль voiceroom: live-аудио ===
CREATE TABLE voice_rooms (
    room_id         UUID PRIMARY KEY,
    title           TEXT NOT NULL,
    owner_id        UUID NOT NULL REFERENCES users,
    community_id    UUID NOT NULL REFERENCES communities,  -- аудио-чат ТОЛЬКО в сообществе
    community_access TEXT DEFAULT 'open',
    restricted_visible BOOLEAN DEFAULT TRUE,
    attached_type   TEXT,                          -- 'group'|'channel'|NULL — слабая ссылка отображения
    attached_id     UUID,                          -- (модули не импортируют друг друга)
    speak_policy    TEXT DEFAULT 'all',            -- 'all'|'by_role'
    livekit_room    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);

CREATE INDEX idx_groups_community   ON groups (community_id)      WHERE community_id IS NOT NULL;
CREATE INDEX idx_channels_community ON channels (community_id)    WHERE community_id IS NOT NULL;
CREATE INDEX idx_voice_community    ON voice_rooms (community_id);

-- Инварианты (уровень приложения):
--   voice_rooms.community_id NOT NULL — «отдельное» создание → авто-сообщество;
--     attached_* указывает только на элемент ТОГО ЖЕ сообщества
--   удаление сообщества: groups/channels → community_id = NULL (standalone);
--     voice_rooms удаляются вместе с сообществом
--   сообщество не вкладывается в сообщество

-- === Подсистема membership: роли всех сущностей (не подписчики!) ===
CREATE TABLE memberships (
    target_type     TEXT NOT NULL,                 -- 'group'|'channel'|'voice_room'|'community'
    target_id       UUID NOT NULL,
    user_id         UUID NOT NULL REFERENCES users,
    role            TEXT NOT NULL DEFAULT 'member',
                    -- group:     owner|admin|moderator|member
                    -- channel:   owner|admin|author        (author: право публикации)
                    -- community: owner|admin|moderator     (communities.md §4)
                    -- voice_room: owner|speaker            (при speak_policy='by_role')
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at         TIMESTAMPTZ,
    banned_until    TIMESTAMPTZ,                   -- модерация: временная блокировка
    PRIMARY KEY (target_type, target_id, user_id)
);
-- Подписчики (каналов, сообществ) — в subscriptions; membership — роли и членство личных групп.
-- Для канала строка может существовать ТОЛЬКО ради banned_until (запрет комментирования подписчику)

-- === Подсистема invites ===
CREATE TABLE invites (
    invite_id       UUID PRIMARY KEY,
    target_type     TEXT NOT NULL,                 -- 'group'|'channel'|'community'
    target_id       UUID NOT NULL,
    created_by      UUID NOT NULL REFERENCES users,   -- для бот-инвайтов = installed_by установки бота
    via_bot         UUID,                             -- actor-бот (аудит; REFERENCES bots)
    expires_at      TIMESTAMPTZ,
    max_uses        INT,
    used_count      INT DEFAULT 0,
    newcomer_role   TEXT DEFAULT 'member'          -- group: роль участника; channel: 'author' или NULL (=подписка); community: NULL (=подписка)
);

CREATE TABLE chat_user_settings (                  -- пер-пользовательские настройки чата/сущности
    user_id         UUID NOT NULL REFERENCES users,
    target_type     TEXT NOT NULL,                 -- 'chat'|'group'|'channel'|'community'
    target_id       UUID NOT NULL,
    archived        BOOLEAN DEFAULT FALSE,         -- архив: скрыть из списка, история сохраняется
    pinned          BOOLEAN DEFAULT FALSE,         -- закрепить чат в списке
    pinned_position INT,
    block_messages  BOOLEAN DEFAULT FALSE,         -- запрет карточек-сообщений ОТ сущности (окно 4);
                                                   --   независим от уведомлений (notification_settings) — «и/или»
    PRIMARY KEY (user_id, target_type, target_id)
);

CREATE TABLE pinned_messages (                     -- закреплённые сообщения чата/группы
    target_type     TEXT NOT NULL,                 -- 'chat'|'group'
    target_id       UUID NOT NULL,
    message_id      BIGINT NOT NULL,
    pinned_by       UUID NOT NULL,
    pinned_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (target_type, target_id, message_id)
);

CREATE TABLE chat_folders (                        -- каталог: ручная сортировка
    folder_id       UUID PRIMARY KEY,
    owner_id        UUID NOT NULL REFERENCES users,
    title           TEXT NOT NULL,
    icon            TEXT,
    position        INT
);

CREATE TABLE chat_folder_items (
    folder_id       UUID REFERENCES chat_folders,
    target_type     TEXT NOT NULL,                 -- 'chat'|'group'|'channel'|'community'
    target_id       UUID NOT NULL,
    position        INT,
    PRIMARY KEY (folder_id, target_type, target_id)
);
-- Папки — личная сортировка каталога владельцем; серверных прав/шаринга нет.
-- Не путать с сообществами (административный контейнер) — communities.md §1
```

## 4. Сообщения (append-only, партиционирование по времени)

```sql
-- Личные сообщения: только ciphertext (формат — crypto-protocol.md §3)
CREATE TABLE personal_messages (
    message_id      BIGINT GENERATED ALWAYS AS IDENTITY,
    chat_id         UUID NOT NULL,
    sender_id       UUID NOT NULL,
    sender_device   UUID NOT NULL,
    kind            TEXT NOT NULL DEFAULT 'text',  -- text|voice|image|video|file|system
    encrypted_payload BYTEA NOT NULL,              -- SecretBox(zstd(protobuf), message_key)
    escrow_blob     BYTEA NOT NULL,
    ratchet_envelope BYTEA,                        -- nullable (путь A)
    reply_to        BIGINT,
    signature       BYTEA NOT NULL,                -- Ed25519
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN DEFAULT FALSE,         -- soft delete «для всех»
    deleted_at      TIMESTAMPTZ,
    deleted_by      UUID,
    PRIMARY KEY (message_id, created_at)
) PARTITION BY RANGE (created_at);                 -- месячные партиции

CREATE INDEX idx_pm_chat ON personal_messages (chat_id, message_id DESC);

-- Wrapped keys: план Б доставки (per получатель × устройство)
CREATE TABLE personal_message_keys (
    message_id      BIGINT NOT NULL,
    recipient_key   UUID NOT NULL,                 -- device_id получателя ИЛИ vu_id (обёртка на identity-ключ ВП)
    wrapped_key     BYTEA NOT NULL,                -- Box(ephemeral, identity_pub, message_key)
    PRIMARY KEY (message_id, recipient_key)
);
CREATE INDEX idx_pmk_recipient ON personal_message_keys (recipient_key, message_id DESC);

-- Сообщения ГРУПП (личные: ciphertext; публичные: plaintext payload).
-- У каналов сообщений нет — их контент только posts (§7, module-boundaries.md §1)
CREATE TABLE group_messages (
    message_id      BIGINT GENERATED ALWAYS AS IDENTITY,
    group_id        UUID NOT NULL,
    sender_id       UUID NOT NULL,
    kind            TEXT NOT NULL DEFAULT 'text',
    sender_type     TEXT NOT NULL DEFAULT 'user',  -- 'user' | 'entity' (от имени группы: бот/система; только публичные группы)
    via_bot         UUID,                          -- actor-бот (аудит; REFERENCES bots)
    gk_version      INT,                           -- NULL для публичных групп
    payload         BYTEA NOT NULL,                -- private: SecretBox(GK); public: protobuf plaintext
    thread_root     BIGINT,                        -- ветки
    reply_to        BIGINT,
    forward_from_group UUID,                       -- forward-ссылка (только публичные группы)
    forward_from_msg   BIGINT,
    signature       BYTEA,                         -- Ed25519 устройства; NULL только при sender_type='entity'
                                                   --   (аутентичность гарантирует сервер: bot token + HMAC)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN DEFAULT FALSE,
    deleted_at      TIMESTAMPTZ,
    deleted_by      UUID,
    PRIMARY KEY (message_id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_gm_group ON group_messages (group_id, message_id DESC);
CREATE INDEX idx_gm_thread ON group_messages (group_id, thread_root) WHERE thread_root IS NOT NULL;

-- Статусы доставки/прочтения (личные чаты и малые группы)
CREATE TABLE message_receipts (
    message_id      BIGINT NOT NULL,
    user_id         UUID NOT NULL,
    delivered_at    TIMESTAMPTZ,
    read_at         TIMESTAMPTZ,                   -- прослушано для voice
    PRIMARY KEY (message_id, user_id)
);
```

## 5. Групповые ключи (крипто)

```sql
CREATE TABLE group_key_history (
    group_id        UUID NOT NULL,
    gk_version      INT NOT NULL,
    escrow_blob     BYTEA NOT NULL,                -- ML-KEM(Escrow_Public, GK)
    rotated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason          TEXT,                          -- periodic|member_join|member_leave
    PRIMARY KEY (group_id, gk_version)
);

CREATE TABLE user_wrapped_keys (
    group_id        UUID NOT NULL,
    gk_version      INT NOT NULL,
    device_id       UUID NOT NULL REFERENCES devices,
    wrapped_gk      BYTEA NOT NULL,
    status          TEXT DEFAULT 'active',         -- active|archived|deleted
    PRIMARY KEY (group_id, gk_version, device_id)
);
```

## 6. Медиа

```sql
CREATE TABLE media_objects (
    media_id        UUID PRIMARY KEY,
    owner_id        UUID NOT NULL,
    content_hash    BYTEA,                         -- SHA-256 plaintext; ТОЛЬКО публичное (CAS для приватного запрещён — crypto-protocol §5)
    storage_key     TEXT NOT NULL,                 -- MinIO path
    is_encrypted    BOOLEAN NOT NULL,
    period_id       INT,                           -- приватное: escrow-период (escrow_periods)
    size_bytes      BIGINT NOT NULL,
    mime_type       TEXT,
    chunk_count     INT DEFAULT 1,
    width           INT, height INT, duration_ms INT,
    tier            TEXT DEFAULT 'hot',            -- hot|warm|cold (lifecycle)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_cas ON media_objects (content_hash) WHERE content_hash IS NOT NULL;
```

Ключи шифрования медиа не хранятся здесь — они внутри `encrypted_payload` сообщений либо в wrapped keys ([media-storage.md](../04-data/media-storage.md)).

```sql
-- Escrow «по периоду» для медиа вне сообщений (коллекции, истории) и вложений:
-- один blob на период, не на файл (crypto-protocol.md §6, escrow-legal-access.md §5)
CREATE TABLE escrow_periods (
    scope_type      TEXT NOT NULL,                 -- 'chat'|'group'|'collection'|'user_media'
    scope_id        UUID NOT NULL,
    period_id       INT NOT NULL,
    escrow_blob     BYTEA NOT NULL,                -- ML-KEM(Escrow_Public, period_key)
    started_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (scope_type, scope_id, period_id)
);
-- media_key периода дополнительно заворачивается period_key при загрузке приватного медиа
```

## 7. Ленты, посты, реакции

```sql
-- === Подсистема publications: все публикации (посты каналов, медиа-посты, статьи, стена) ===
-- Формат контента: plain text + entities (module-boundaries.md §5)
CREATE TABLE posts (
    post_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    author_type     TEXT NOT NULL,                 -- 'user'|'channel' (ВП и «псевдонимы» = 'user', §10)
    author_id       UUID NOT NULL,
    kind            TEXT NOT NULL,                 -- text|article|photo|video
    title           TEXT,                          -- статьи
    body            TEXT,
    entities        JSONB,                         -- [{type, offset, length, ...}] — bold, heading, link, mention, hashtag, media…
    media_ids       UUID[],
    status          TEXT NOT NULL DEFAULT 'published', -- 'pending' (премодерация) | 'published' | 'scheduled'
    scheduled_at    TIMESTAMPTZ,                   -- отложенная публикация
    via_bot         UUID,                          -- actor-бот (аудит; REFERENCES bots)
    fts             TSVECTOR GENERATED ALWAYS AS (to_tsvector('russian', coalesce(title,'') || ' ' || coalesce(body,''))) STORED,
    plus_count      INT DEFAULT 0,                 -- [+]
    minus_count     INT DEFAULT 0,                 -- [−]
    comment_count   INT DEFAULT 0,                 -- денормализация (обновляет модуль comments)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_posts_fts ON posts USING gin(fts);
CREATE INDEX idx_posts_author ON posts (author_type, author_id, post_id DESC);

CREATE TABLE post_drafts (                         -- черновики редактора (34-content-editor)
    draft_id        UUID PRIMARY KEY,
    editor_user     UUID NOT NULL REFERENCES users, -- кто редактирует
    author_type     TEXT NOT NULL,                 -- от чьего имени: 'user'|'channel' (ВП = 'user')
    author_id       UUID NOT NULL,
    kind            TEXT,
    title           TEXT,
    body            TEXT,
    entities        JSONB,
    media_ids       UUID[],
    attribute_ids   UUID[],
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_drafts_editor ON post_drafts (editor_user, updated_at DESC);

-- === Подсистема attributes: атрибуты (= хэштеги) и жанры (feed-ranking.md §3) ===
CREATE TABLE genres (                              -- курируемые сервером «папки» атрибутов
    genre_id        UUID PRIMARY KEY,
    title           TEXT NOT NULL,
    position        INT
);

CREATE TABLE attributes (                          -- фолксономия: создают авторы при публикации
    attribute_id    UUID PRIMARY KEY,
    name            TEXT UNIQUE NOT NULL,          -- нормализованное (lowercase); #хэштег в тексте резолвится сюда
    display_name    TEXT NOT NULL,
    description     TEXT,
    genre_id        UUID REFERENCES genres,        -- 0..1; назначает СЕРВЕР (курирование/автоклассификация)
    proposed_genre  UUID REFERENCES genres,        -- что предложил автор при создании
    created_by      UUID NOT NULL REFERENCES users,
    post_count      INT DEFAULT 0,
    follower_count  INT DEFAULT 0,
    merged_into     UUID REFERENCES attributes,    -- слияние дублей: редирект на канонический
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE post_attributes (                     -- метка поста + одобрение «автор + репутация»
    post_id         BIGINT NOT NULL,
    attribute_id    UUID NOT NULL REFERENCES attributes,
    status          TEXT NOT NULL DEFAULT 'declared',  -- declared | approved | rejected (feed-ranking.md §4)
    PRIMARY KEY (post_id, attribute_id)
);
CREATE INDEX idx_pa_attr ON post_attributes (attribute_id, post_id DESC) WHERE status = 'approved';

CREATE TABLE user_attributes (                     -- «мои атрибуты» → чипсы и тематические срезы
    user_id         UUID NOT NULL REFERENCES users,
    attribute_id    UUID NOT NULL REFERENCES attributes,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, attribute_id)
);

CREATE TABLE recommendations (                     -- [+]/[−], только публичное
    user_id         UUID NOT NULL,
    target_type     TEXT NOT NULL,                 -- 'post'|'group_message'
    target_id       BIGINT NOT NULL,
    value           SMALLINT NOT NULL CHECK (value IN (-1, 1)),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, target_type, target_id)
);

CREATE TABLE emotions (                            -- шкала 9 эмоций, одна на сообщение
    user_id         UUID NOT NULL,
    target_type     TEXT NOT NULL,                 -- 'personal_message'|'group_message'|'post'|'comment'
    target_id       BIGINT NOT NULL,
    emotion         SMALLINT NOT NULL CHECK (emotion BETWEEN 1 AND 9),
                    -- валентность +1: 1 одобрение, 3 смех, 5 ярость, 7 интерес
                    -- валентность −1: 2 презрение, 4 боль, 6 страх, 8 скука
                    -- нейтральная (в рейтинг не пишет): 9 спокойствие
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, target_type, target_id)
);

-- Раздельные рейтинговые счётчики (пример: «+33 −22»); НЕ суммируются.
-- Обновляются воркером по событиям insert/update/delete в emotions:
-- эмоция 1..8 → инкремент positive или negative у автора контента и у группы/канала.
CREATE TABLE rating_counters (
    subject_type    TEXT NOT NULL,                 -- 'user'|'group'|'channel' (рейтинг ВП — отдельный 'user')
    subject_id      UUID NOT NULL,
    positive        BIGINT NOT NULL DEFAULT 0,
    negative        BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (subject_type, subject_id)
);

-- === Подсистема comments: ТОЛЬКО публичный контур (посты, публичные медиа/коллекции).
-- В защищённом контуре обсуждение = сообщения/ветки групп. module-boundaries.md §2
CREATE TABLE comments (
    comment_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    target_type     TEXT NOT NULL,                 -- 'post'|'media'|'collection_item'
    target_id       BIGINT NOT NULL,
    author_id       UUID NOT NULL,
    body            TEXT NOT NULL,
    reply_to        BIGINT,                        -- один уровень отступа; глубже — @username (15-comments)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN DEFAULT FALSE
);
CREATE INDEX idx_comments_target ON comments (target_type, target_id, comment_id);

-- === Избранное: две полки (feed-ranking.md §2) ===
CREATE TABLE favorites (                           -- ПУБЛИЧНАЯ полка: видна друзьям, питает ленту друзей
    user_id         UUID NOT NULL,
    target_type     TEXT NOT NULL,
    target_id       BIGINT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, target_type, target_id)
);

-- ЛИЧНАЯ полка: шифруется, в ленты не попадает никогда; доступ по запросу (wrapped shelf_key)
CREATE TABLE private_shelves (
    owner_id        UUID PRIMARY KEY REFERENCES users,
    encrypted_payload BYTEA NOT NULL,              -- SecretBox(список закладок, shelf_key)
    escrow_blob     BYTEA NOT NULL,                -- ML-KEM(Escrow_Public, shelf_key) — ADR-0004: escrow всегда
    key_version     INT NOT NULL DEFAULT 1,        -- ротация при отзыве доступа
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shelf_access (                        -- запрос/выдача доступа к личной полке
    owner_id        UUID NOT NULL,
    grantee_id      UUID NOT NULL,
    status          TEXT NOT NULL DEFAULT 'requested', -- requested | granted | revoked
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, grantee_id)
);

CREATE TABLE shelf_wrapped_keys (                  -- shelf_key на устройства владельца и грантополучателей
    owner_id        UUID NOT NULL,
    device_id       UUID NOT NULL REFERENCES devices,
    wrapped_key     BYTEA NOT NULL,                -- Box(ephemeral, device_identity, shelf_key)
    PRIMARY KEY (owner_id, device_id)
);
-- Отзыв доступа = ротация shelf_key + перевыдача wrapped_key оставшимся (feed-ranking.md §2)
```

Лента `feed:{user_id}` материализуется в Redis (fan-out воркером), в PG — источник постов.

## 8. Истории и коллекции

```sql
CREATE TABLE stories (
    story_id        UUID PRIMARY KEY,
    author_type     TEXT NOT NULL,                 -- 'user'|'group'|'channel'
    author_id       UUID NOT NULL,
    media_id        UUID NOT NULL,
    is_encrypted    BOOLEAN NOT NULL,              -- личные истории шифруются (story_key)
    audience        TEXT NOT NULL DEFAULT 'friends', -- 'public'|'friends'|'group' (таргетинг аудитории)
    audience_group  UUID,                          -- при audience='group'
    escrow_blob     BYTEA,                         -- личные: ML-KEM(Escrow_Public, story_key)
    expires_at      TIMESTAMPTZ NOT NULL,          -- created_at + 24h
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE story_keys (                          -- личные истории: story_key на устройства аудитории
    story_id        UUID NOT NULL,
    device_id       UUID NOT NULL REFERENCES devices,
    wrapped_key     BYTEA NOT NULL,                -- создаётся при публикации (аудитория известна); TTL 24ч ограничивает объём
    PRIMARY KEY (story_id, device_id)
);

CREATE TABLE collections (
    collection_id   UUID PRIMARY KEY,
    owner_id        UUID NOT NULL,
    title           TEXT NOT NULL,
    privacy         TEXT NOT NULL,                 -- personal|shared|public (матрица безопасности)
    cover_media_id  UUID,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE collection_members (                  -- совместные коллекции
    collection_id   UUID REFERENCES collections,
    user_id         UUID REFERENCES users,
    role            TEXT DEFAULT 'contributor',
    PRIMARY KEY (collection_id, user_id)
);

CREATE TABLE collection_keys (                     -- личные/совместные: collection_key на устройства участников
    collection_id   UUID NOT NULL,
    key_version     INT NOT NULL,                  -- ротация при смене участников
    device_id       UUID NOT NULL REFERENCES devices,
    wrapped_key     BYTEA NOT NULL,
    PRIMARY KEY (collection_id, key_version, device_id)
);
-- escrow ключа коллекции — через escrow_periods (scope_type='collection', §6)

CREATE TABLE collection_items (
    collection_id   UUID REFERENCES collections,
    item_id         BIGINT GENERATED ALWAYS AS IDENTITY,
    media_id        UUID NOT NULL,
    added_by        UUID NOT NULL,
    position        INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, item_id)
);
```

## 9. Звонки, уведомления, модерация

```sql
CREATE TABLE calls (
    call_id         UUID PRIMARY KEY,
    kind            TEXT NOT NULL,                 -- audio|video|voice_room
    initiator_id    UUID NOT NULL,
    chat_id         UUID,                          -- 1:1
    group_id        UUID,                          -- групповой звонок из группы
    voice_room_id   UUID,                          -- сессия аудио-чата (voice_rooms)
    livekit_room    TEXT NOT NULL,
    started_at      TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    outcome         TEXT                           -- completed|missed|declined
);

CREATE TABLE notification_settings (
    user_id         UUID NOT NULL,
    scope_type      TEXT NOT NULL,                 -- 'global'|'chat'|'group'|'event_type'
    scope_id        TEXT NOT NULL DEFAULT '',
    mode            TEXT NOT NULL,                 -- all|important|mute
    quiet_from      TIME, quiet_to TIME,
    PRIMARY KEY (user_id, scope_type, scope_id)
);

CREATE TABLE reports (                             -- жалобы
    report_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reporter_id     UUID NOT NULL,
    target_type     TEXT NOT NULL,                 -- user|message|group|post
    target_id       TEXT NOT NULL,
    reason          TEXT,
    status          TEXT DEFAULT 'open',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

## 10. Виртуальные пользователи

ВП — обычная строка `users` (`account_type='virtual'`, `owner_user_id`), поэтому memberships/posts/emotions/подписки работают без изменений ([virtual-users.md](../01-product/virtual-users.md)). Блогерские «псевдонимы» — это ВП; отдельной таблицы aliases нет.

```sql
-- В users (§1): account_type += 'virtual'; owner_user_id UUID REFERENCES users
--   (NULL для обычных; связь известна только серверу и операторам)

CREATE TABLE vu_operators (                        -- операторы ВП (назначает владелец)
    vu_id           UUID NOT NULL REFERENCES users,   -- ВП
    operator_id     UUID NOT NULL REFERENCES users,   -- реальный пользователь
    role            TEXT NOT NULL DEFAULT 'operator', -- operator|manager (manager: правит операторов)
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    PRIMARY KEY (vu_id, operator_id)
);

CREATE TABLE vu_wrapped_keys (                     -- приватные ключи ВП: только wrapped на устройства
    vu_id           UUID NOT NULL REFERENCES users,
    device_id       UUID NOT NULL REFERENCES devices, -- устройство владельца или оператора
    wrapped_identity BYTEA NOT NULL,
    wrapped_signing  BYTEA NOT NULL,
    key_version     INT NOT NULL DEFAULT 1,           -- ротация при смене операторов/передаче
    PRIMARY KEY (vu_id, device_id, key_version)
);

CREATE TABLE vu_transfers (                        -- аудит передач (продаж) ВП
    transfer_id     UUID PRIMARY KEY,
    vu_id           UUID NOT NULL REFERENCES users,
    from_user       UUID NOT NULL,
    to_user         UUID NOT NULL,
    initiated_at    TIMESTAMPTZ NOT NULL,
    confirmed_at    TIMESTAMPTZ,                      -- двустороннее согласие
    key_rotated     BOOLEAN DEFAULT TRUE
);

CREATE TABLE vu_audit (                            -- кто реально действовал от имени ВП
    audit_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    vu_id           UUID NOT NULL,
    actor_user_id   UUID NOT NULL,                    -- публично не раскрывается
    action          TEXT NOT NULL,                    -- message|post|moderation|settings|…
    target_ref      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Статистика ВП (просмотры/охваты) — агрегаты по posts/emotions/subscriptions (view, фаза 5+)
```

## 11. Социальное взаимодействие (окно 4)

Агрегатор карточек ([10-social-interaction](../doc_UI/10-social-interaction.md)): обращения — командные (серверный статус/ответственный), события — личные (только read-state).

```sql
CREATE TABLE inbox_threads (                       -- обращения: к ВП (E2E) и к сущностям (plaintext)
    thread_id       UUID PRIMARY KEY,
    identity_type   TEXT NOT NULL,                   -- 'user' (ВП/осн. аккаунт) | 'group'|'channel'|'community'
    identity_id     UUID NOT NULL,
    chat_id         UUID,                            -- identity_type='user': настоящий E2E-чат обращения
                                                     -- сущности: NULL — тред в appeal_messages (plaintext)
    from_user       UUID NOT NULL,
    source_type     TEXT,                            -- контекст: channel|group|community|direct
    source_id       UUID,
    status          TEXT NOT NULL DEFAULT 'new',     -- new|taken|snoozed|closed (серверный, общий)
    assignee_id     UUID,                            -- ответственный (владелец/оператор)
    snoozed_until   TIMESTAMPTZ,
    priority        SMALLINT DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inbox_identity ON inbox_threads (identity_type, identity_id, status, updated_at DESC);

-- Публичные обращения к сущностям (боты/операторы; E2E-обращения к ВП живут в личных чатах)
CREATE TABLE appeal_messages (
    thread_id       UUID NOT NULL REFERENCES inbox_threads,
    msg_id          BIGINT GENERATED ALWAYS AS IDENTITY,
    author_side     TEXT NOT NULL,                   -- 'user' | 'entity' (оператор или бот; факт бота — в bot_audit)
    body            TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (thread_id, msg_id)
);

CREATE TABLE inbox_events (                        -- личные события агрегатора
    event_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         UUID NOT NULL,                   -- получатель (владелец окна 4)
    identity_id     UUID,                            -- от имени какого ВП (NULL = основной)
    event_type      TEXT NOT NULL,                   -- appeal|entity_message (сообщение от сущности/бота)|reply|mention|reaction|comment|role_assigned|moderation_request|thread_activity
    source_type     TEXT, source_id UUID,
    target_ref      TEXT,                            -- ссылка на сообщение/пост/комментарий
    read_at         TIMESTAMPTZ,                     -- личный read-state
    hidden          BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_inbox_events ON inbox_events (user_id, created_at DESC) WHERE NOT hidden;

CREATE TABLE social_inbox_preferences (            -- правила агрегации; синхронизируются между устройствами
    user_id         UUID NOT NULL,
    rule_id         UUID NOT NULL,
    source_selector JSONB NOT NULL,                  -- {identity_id?|source_type?|source_id?}
    event_types     TEXT[] NOT NULL,
    route           TEXT NOT NULL DEFAULT 'inbox',   -- inbox|threads|reactions|hidden
    push            BOOLEAN DEFAULT TRUE,            -- исполняет модуль notifications (26-notifications)
    priority        SMALLINT DEFAULT 0,
    quiet_hours     JSONB,
    PRIMARY KEY (user_id, rule_id)
);
```

## 12. Боты

Бот — не пользователь: приложение с токеном, установленное в сущность; работает только в публичном контуре ([bot-api.md](../05-api/bot-api.md)).

```sql
CREATE TABLE bots (
    bot_id          UUID PRIMARY KEY,
    owner_id        UUID NOT NULL REFERENCES users,
    title           TEXT NOT NULL,
    description     TEXT,
    token_hash      BYTEA NOT NULL,                -- токен показывается один раз
    hmac_key_hash   BYTEA NOT NULL,
    webhook_url     TEXT,
    webhook_secret  BYTEA,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);

CREATE TABLE bot_installations (
    bot_id          UUID NOT NULL REFERENCES bots,
    target_type     TEXT NOT NULL,                 -- 'group'|'channel'|'community' (ТОЛЬКО публичный контур:
    target_id       UUID NOT NULL,                 --  установка в личную E2E-группу запрещена)
    scopes          TEXT[] NOT NULL,               -- send_messages|moderate|publish_posts|invite_links|inbox|notify
    installed_by    UUID NOT NULL REFERENCES users,
    installed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ,
    PRIMARY KEY (bot_id, target_type, target_id)
);

CREATE TABLE bot_update_cursors (                  -- getUpdates: подтверждённый offset
    bot_id          UUID PRIMARY KEY REFERENCES bots,
    last_update_id  BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE bot_audit (                           -- кто (какой бот) действовал от имени сущности
    audit_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bot_id          UUID NOT NULL REFERENCES bots,
    target_type     TEXT NOT NULL, target_id UUID NOT NULL,
    action          TEXT NOT NULL,                 -- message|post|moderation|invite|appeal|notify
    target_ref      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Очередь updates — Redis Streams per bot; в контентных таблицах actor-бот = колонка via_bot
```

## 13. Принципы эволюции

- **Шардинг**: все запросы к сообщениям идут через интерфейс `GetShard(chatID)`; на MVP один узел. Партиции по `created_at` готовят hot/cold-разделение.
- **Retention**: экспирация историй, автоудаление сообщений и политика escrow-ключей — фоновые джобы; политика периодов — [escrow-legal-access.md](../03-security/escrow-legal-access.md).
- **Индексы** добавляются по фактическим планам запросов; выше — только заведомо необходимые.
