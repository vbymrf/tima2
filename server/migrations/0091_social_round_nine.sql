-- Социум, девятый–одиннадцатый круг (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §1.10–§1.12, СЦ18–СЦ23; заказчик 2026-11-23).
-- Всё аддитивно: новые столбцы с умолчанием, новая таблица.

-- СЦ21. У канала — админ, как у группы. Роль — столбцом у той же строки: нынешние модераторы остаются модераторами,
-- все проверки «владелец или модератор» (строка есть) пускают и админа — у него права шире.
ALTER TABLE channel_moderators ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'moderator';
ALTER TABLE channel_moderators DROP CONSTRAINT IF EXISTS chk_channel_moderators_role;
ALTER TABLE channel_moderators ADD CONSTRAINT chk_channel_moderators_role CHECK (role IN ('admin', 'moderator'));

-- СЦ19. «Показывать участников»: по умолчанию показываем (заказчик 2026-11-23, вопрос 5). Своя у каждой сущности.
ALTER TABLE groups      ADD COLUMN IF NOT EXISTS show_members BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE channels    ADD COLUMN IF NOT EXISTS show_members BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS show_members BOOLEAN NOT NULL DEFAULT TRUE;

-- СЦ21. Медленный режим комментариев канала (секунды; 0 — выключен), как `groups.slow_mode_sec`.
ALTER TABLE channels ADD COLUMN IF NOT EXISTS slow_mode_sec INT NOT NULL DEFAULT 0;

-- СЦ21, СЦ23. Бан подписчика канала и сообщества: не подписывается снова, пока не снимут. Бан сообщества закрывает
-- вход и в его каналы и группы — это проверяется по `community_id` элемента, отдельных строк на элементы нет.
CREATE TABLE IF NOT EXISTS entity_bans (
    kind      TEXT        NOT NULL CHECK (kind IN ('channel', 'community')),
    entity_id UUID        NOT NULL,
    user_id   UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    banned_by UUID        NOT NULL,
    banned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (kind, entity_id, user_id)
);

-- СЦ22. Тело записи и комментария канала тем же документом, что у сообщения (MessageBody открытым, ADR-0011 §4):
-- блоки и вложения. Прежние `text`, `nodes`, `markup` остаются — по ним поиск и прежний показ.
ALTER TABLE channel_posts ADD COLUMN IF NOT EXISTS body BYTEA;

-- СЦ18. Правка записи канала и сообщения сообщества (список «Управление → Сообщения»): время правки, 0 — не правлено.
ALTER TABLE channel_posts      ADD COLUMN IF NOT EXISTS edited_at_unix_ms BIGINT NOT NULL DEFAULT 0;
ALTER TABLE community_messages ADD COLUMN IF NOT EXISTS edited_at_unix_ms BIGINT NOT NULL DEFAULT 0;
