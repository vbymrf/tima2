-- 0073: «зашли, забрали» и уведомления по настройке на сервере (ПЛАН-(ОУ)-ОПТИМИЗАЦИИ-УВЕДОМЛЕНИЙ,
-- ОУ2–ОУ4). Строки — в той же ленте состояний, что и 0072: номер на человека, `state.poke`.

-- Вершина сущности «зашли, забрали» (открытая группа, канал) в списке подписчика: последнее
-- сообщение или пост. Одна строка на пару «человек — сущность», заменяется, а не копится.
CREATE TABLE IF NOT EXISTS entity_tops (
    owner_id   UUID        NOT NULL,
    kind       TEXT        NOT NULL CHECK (kind IN ('group', 'channel')),
    entity_id  UUID        NOT NULL,
    top_id     BIGINT      NOT NULL,
    top_at_ms  BIGINT      NOT NULL,
    rev        BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, kind, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_entity_tops_rev ON entity_tops (owner_id, rev);

-- Докуда человек прочитал сущность «зашли, забрали»: номер сообщения или поста. Только вверх.
CREATE TABLE IF NOT EXISTS entity_reads (
    owner_id  UUID   NOT NULL,
    kind      TEXT   NOT NULL CHECK (kind IN ('group', 'channel')),
    entity_id UUID   NOT NULL,
    read_id   BIGINT NOT NULL,
    PRIMARY KEY (owner_id, kind, entity_id)
);

-- «Отключить уведомления» у сущности (решения 3, 5). Строки нет — включено (по умолчанию).
-- `off = false` остаётся строкой: её смена — изменение ленты, и его надо разнести по устройствам.
CREATE TABLE IF NOT EXISTS notify_settings (
    owner_id   UUID        NOT NULL,
    kind       TEXT        NOT NULL CHECK (kind IN ('chat', 'group', 'channel', 'community')),
    entity_id  UUID        NOT NULL,
    off        BOOLEAN     NOT NULL,
    rev        BIGINT      NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, kind, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_notify_settings_rev ON notify_settings (owner_id, rev);

-- Способ доставки устройства (ОУ4): '' — прежний (открытые группы целиком в журнал),
-- 'tops' — открытые группы вершиной. Заявляет само устройство; установленные клиенты молчат и
-- получают по-прежнему.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS delivery TEXT NOT NULL DEFAULT '';
