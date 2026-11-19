-- Владение сущностью по ключу личности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md §6, СЦ11).
--
-- Цепочка владения — истина; `owner_id` у группы, канала, сообщества остаётся быстрым указателем и меняется той же
-- транзакцией, что и новое звено. Звенья только добавляются. Каждое звено передачи подписано ключом личности
-- предыдущего звена и ключом принимающего — подпись акта `tima.entity-transfer.v1|…` (вектор `entity_transfer`).
CREATE TABLE IF NOT EXISTS entity_ownership (
    entity_kind  TEXT        NOT NULL CHECK (entity_kind IN ('group', 'channel', 'community')),
    entity_id    UUID        NOT NULL,
    link_no      INTEGER     NOT NULL CHECK (link_no >= 1),
    owner_id     UUID        NOT NULL REFERENCES users (user_id),
    -- Ключ личности владельца на момент звена; NULL — у аккаунта ключа не было (передать нечем).
    identity_pub BYTEA       CHECK (identity_pub IS NULL OR octet_length(identity_pub) = 32),
    link_kind    TEXT        NOT NULL CHECK (link_kind IN ('genesis', 'transfer', 'rebind')),
    prev_sig     BYTEA,
    new_sig      BYTEA,
    expires_unix BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_kind, entity_id, link_no)
);

-- Первое звено — при создании, без фразы: ключ личности создателя из `users` (§6.2, 2). Триггером, чтобы ни один путь
-- создания (мастер, временная группа звонка, служебная группа) не оставил сущность без начала цепочки.
CREATE OR REPLACE FUNCTION entity_ownership_genesis() RETURNS trigger AS $$
DECLARE
    kind TEXT := TG_ARGV[0];
    id   UUID;
BEGIN
    IF kind = 'group' THEN id := NEW.group_id;
    ELSIF kind = 'channel' THEN id := NEW.channel_id;
    ELSE id := NEW.community_id;
    END IF;
    INSERT INTO entity_ownership (entity_kind, entity_id, link_no, owner_id, identity_pub, link_kind)
    SELECT kind, id, 1, NEW.owner_id, u.identity_pub, 'genesis' FROM users u WHERE u.user_id = NEW.owner_id
    ON CONFLICT DO NOTHING;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS groups_ownership_genesis ON groups;
CREATE TRIGGER groups_ownership_genesis AFTER INSERT ON groups
    FOR EACH ROW EXECUTE FUNCTION entity_ownership_genesis('group');
DROP TRIGGER IF EXISTS channels_ownership_genesis ON channels;
CREATE TRIGGER channels_ownership_genesis AFTER INSERT ON channels
    FOR EACH ROW EXECUTE FUNCTION entity_ownership_genesis('channel');
DROP TRIGGER IF EXISTS communities_ownership_genesis ON communities;
CREATE TRIGGER communities_ownership_genesis AFTER INSERT ON communities
    FOR EACH ROW EXECUTE FUNCTION entity_ownership_genesis('community');

-- У существующих сущностей первое звено — из `owner_id` (§6.2, 2).
INSERT INTO entity_ownership (entity_kind, entity_id, link_no, owner_id, identity_pub, link_kind)
SELECT 'group', g.group_id, 1, g.owner_id, u.identity_pub, 'genesis'
  FROM groups g JOIN users u ON u.user_id = g.owner_id
ON CONFLICT DO NOTHING;
INSERT INTO entity_ownership (entity_kind, entity_id, link_no, owner_id, identity_pub, link_kind)
SELECT 'channel', c.channel_id, 1, c.owner_id, u.identity_pub, 'genesis'
  FROM channels c JOIN users u ON u.user_id = c.owner_id
ON CONFLICT DO NOTHING;
INSERT INTO entity_ownership (entity_kind, entity_id, link_no, owner_id, identity_pub, link_kind)
SELECT 'community', c.community_id, 1, c.owner_id, u.identity_pub, 'genesis'
  FROM communities c JOIN users u ON u.user_id = c.owner_id
ON CONFLICT DO NOTHING;

-- Предложение передачи: одно живое на сущность, 30 минут, три неверные фразы гасят (§6.2, 3).
CREATE TABLE IF NOT EXISTS entity_transfer_offers (
    entity_kind  TEXT        NOT NULL CHECK (entity_kind IN ('group', 'channel', 'community')),
    entity_id    UUID        NOT NULL,
    link_no      INTEGER     NOT NULL,
    from_id      UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    to_id        UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    expires_unix BIGINT      NOT NULL,
    owner_sig    BYTEA       NOT NULL,
    attempts     INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (entity_kind, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_entity_transfer_offers_to ON entity_transfer_offers (to_id);
