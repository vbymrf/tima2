-- 0048: сообщества (ПЛАН-СООБЩЕСТВ С1, ADR-0019).
--
-- СООБЩЕСТВО НИЧЕГО НЕ ПЕРЕСЫЛАЕТ И НЕ ЗАБИРАЕТ СЕБЕ ЧУЖОГО. Оно называет, что с чем
-- связано, и держит роли. Внесли группу — поменялась одна ссылка; переписка, участники и
-- ключи не тронуты, и отвязать так же дёшево.
--
-- Своё содержимое у него ровно одно — **сообщения уровня 0**, то есть описание
-- (уточнение заказчика 2026-09-08). Отдельного поля витрины не заводится: это то же
-- решение, что отменило `groups.showcase` (ADR-0019 §4).
CREATE TABLE IF NOT EXISTS communities (
    community_id UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    title        TEXT        NOT NULL,
    owner_id     UUID        NOT NULL REFERENCES users(user_id),
    -- Видно ли сообщество в каталоге. Состав при этом всё равно показывается по правам:
    -- публичная страница не значит «всё внутри публично».
    is_public    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ
);

-- ОПИСАНИЕ — СООБЩЕНИЯ УРОВНЯ 0, а не колонка `description`.
--
-- По образцу `group_messages`, но без шифра и без подписи: уровень 0 значит «всем и
-- всегда», у него нет ни ключа, ни адресата, а подпись устройства завела бы здесь ту же
-- цепочку проверок, что и в группе, ради текста, который и так открыт всем.
--
-- Уровней выше нуля у сообщества нет: переписка идёт в группах и каналах внутри, а не в
-- контейнере. Ограничение это и держит.
CREATE TABLE IF NOT EXISTS community_messages (
    message_id         BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    community_id       UUID        NOT NULL REFERENCES communities(community_id) ON DELETE CASCADE,
    author_id          UUID        NOT NULL REFERENCES users(user_id),
    -- Узлы и разметка открытым текстом — ADR-0011 §4, публичный контур.
    nodes              TEXT[]      NOT NULL DEFAULT '{}',
    markup             JSONB,
    markup_version     INT         NOT NULL DEFAULT 1,
    level              SMALLINT    NOT NULL DEFAULT 0 CHECK (level = 0),
    created_at_unix_ms BIGINT      NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted            BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_cm_community
    ON community_messages (community_id, message_id DESC) WHERE NOT deleted;

-- СОСТАВ — КОЛОНКА У ЭЛЕМЕНТА, А НЕ ТАБЛИЦА СВЯЗЕЙ.
--
-- Таблица связей позволяет элементу быть в двух сообществах, а это ровно то, что
-- запрещено: тогда «кто здесь распоряжается» перестаёт иметь единственный ответ. Запрет,
-- который держит база, переживает любого, кто напишет второй путь добавления.
--
-- NULL значит «отдельный» — так лежат все существующие группы и каналы, и это же
-- состояние возвращается при отвязывании.
--
-- У `voice_rooms` колонки НЕТ намеренно: звуковой чат ждёт реализации (решение заказчика
-- 2026-09-08), и до неё сообщество о нём ничего не знает.
-- У `groups` колонка уже есть с миграции 0005 — заведена без внешнего ключа, с прямой
-- запиской «FK появится с модулем сообществ». Он появился: ссылка ставится здесь, и
-- ставится именно сейчас, потому что до этой миграции ссылаться было не на что.
ALTER TABLE groups   ADD COLUMN IF NOT EXISTS community_id UUID;
ALTER TABLE channels ADD COLUMN IF NOT EXISTS community_id UUID;

ALTER TABLE groups   DROP CONSTRAINT IF EXISTS fk_groups_community;
ALTER TABLE groups   ADD CONSTRAINT fk_groups_community
    FOREIGN KEY (community_id) REFERENCES communities(community_id);
ALTER TABLE channels DROP CONSTRAINT IF EXISTS fk_channels_community;
ALTER TABLE channels ADD CONSTRAINT fk_channels_community
    FOREIGN KEY (community_id) REFERENCES communities(community_id);

CREATE INDEX IF NOT EXISTS idx_groups_community   ON groups (community_id)   WHERE community_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_channels_community ON channels (community_id) WHERE community_id IS NOT NULL;

-- ПОДПИСКА ОФОРМЛЯЕТСЯ НА СООБЩЕСТВО — одно действие на весь контейнер (С3).
--
-- Отдельная таблица, а не полиморфные `subscriptions`: у канала подписка означает
-- «читаю», у сообщества — «читаю его каналы». Свести их в одну строку значило бы
-- потерять это различие в первом же запросе.
CREATE TABLE IF NOT EXISTS community_subscriptions (
    community_id  UUID        NOT NULL REFERENCES communities(community_id) ON DELETE CASCADE,
    subscriber_id UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (community_id, subscriber_id)
);
CREATE INDEX IF NOT EXISTS idx_community_subs_user ON community_subscriptions (subscriber_id);

-- РОЛИ: владелец лежит в `communities.owner_id`, админ — строкой здесь.
--
-- Перечисления ролей нет по той же причине, что у модераторов канала: двух значений, одно
-- из которых уже хранится в другом месте, хватает, а перечисление их рассинхронизирует.
--
-- Ниже этих двух — роли самих элементов, как есть: админ сообщества НЕ становится админом
-- личной группы внутри. Там ключи и состав, и вступление остаётся заявкой (ADR-0018).
CREATE TABLE IF NOT EXISTS community_admins (
    community_id UUID        NOT NULL REFERENCES communities(community_id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    added_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (community_id, user_id)
);
