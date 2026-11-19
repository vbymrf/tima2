-- Журнал ролей сущности (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ6): кто, кому, что, когда. Только добавляется.
-- action: add | remove | role | ban; detail — новая роль или срок бана в секундах.
CREATE TABLE IF NOT EXISTS entity_role_log (
    log_id      BIGSERIAL   PRIMARY KEY,
    entity_kind TEXT        NOT NULL CHECK (entity_kind IN ('group', 'channel', 'community')),
    entity_id   UUID        NOT NULL,
    actor_id    UUID        NOT NULL,
    target_id   UUID        NOT NULL,
    action      TEXT        NOT NULL CHECK (action IN ('add', 'remove', 'role', 'ban')),
    detail      TEXT        NOT NULL DEFAULT '',
    at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_entity_role_log ON entity_role_log (entity_kind, entity_id, log_id DESC);
