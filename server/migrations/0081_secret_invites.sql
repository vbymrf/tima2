-- Секретные сущности и приглашения (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2).
--
-- «Секретная» — свой признак, с `is_public` не сливается: у канала `is_public = false` значит «читают только
-- подписчики», и лента человека — тоже такой канал. Секретная сущность не находится нигде — ни поиском, ни
-- `discover`, ни карточкой, ни страницей сообщества у постороннего; войти в неё можно только по приглашению.
ALTER TABLE groups      ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE channels    ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE communities ADD COLUMN IF NOT EXISTS hidden BOOLEAN NOT NULL DEFAULT FALSE;

-- Приглашение — поимённое: кто, кого, куда, до какого срока, что ответил. Сам показ приглашения — карточкой в
-- личной переписке с пригласившим (её шлёт клиент, сквозным шифрованием); сервер держит только состояние, по
-- которому принимает в секретную сущность. Одно живое приглашение на человека и сущность.
CREATE TABLE IF NOT EXISTS entity_invites (
    invite_id   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_kind TEXT        NOT NULL CHECK (entity_kind IN ('group', 'channel', 'community')),
    entity_id   UUID        NOT NULL,
    inviter_id  UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    invitee_id  UUID        NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
    state       TEXT        NOT NULL DEFAULT 'pending' CHECK (state IN ('pending', 'accepted', 'declined')),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL DEFAULT now() + INTERVAL '30 days',
    answered_at TIMESTAMPTZ,
    UNIQUE (entity_kind, entity_id, invitee_id)
);
CREATE INDEX IF NOT EXISTS idx_entity_invites_invitee ON entity_invites (invitee_id, state);
