-- 0003: метаданные медиа (media-storage.md; бинарники — только в MinIO).

CREATE TABLE IF NOT EXISTS media_objects (
    media_id     UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID        NOT NULL,               -- кто загрузил (квоты, права на complete)
    storage_key  TEXT        NOT NULL,               -- ключ в bucket (чанки: префикс + /chunk-N)
    mime         TEXT        NOT NULL DEFAULT '',
    size_bytes   BIGINT      NOT NULL,               -- заявлено при init; после complete — фактический
    is_encrypted BOOLEAN     NOT NULL,               -- приватное (SecretBox на клиенте) или публичное
    content_hash BYTEA,                              -- SHA-256 plaintext: ТОЛЬКО публичное (CAS §5)
    chunk_count  INT         NOT NULL DEFAULT 1,
    status       TEXT        NOT NULL DEFAULT 'pending',  -- 'pending' | 'complete'
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- CAS: один complete-объект на hash; для приватного hash не хранится вовсе
CREATE UNIQUE INDEX IF NOT EXISTS idx_media_cas
    ON media_objects(content_hash) WHERE content_hash IS NOT NULL AND status = 'complete';
CREATE INDEX IF NOT EXISTS idx_media_owner ON media_objects(owner_id, created_at DESC);
