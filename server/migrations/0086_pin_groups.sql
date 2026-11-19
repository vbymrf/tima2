-- Закреплённые группой (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ12; влито из ПЛАН-(ЗГ)).
--
-- Закреплённое — одно сообщение или группа до 20 на переписку, группу, канал; у группы есть название (по умолчанию —
-- «Группа сообщений» на клиенте). Хранится на сервере открыто, как и прежде (ЗО-1). Прежняя таблица `pins` переносится
-- первым пунктом и больше не пишется: прежние ручки `…/pin` работают поверх списка.
CREATE TABLE IF NOT EXISTS pin_items (
    container_kind TEXT        NOT NULL CHECK (container_kind IN ('personal', 'group', 'channel')),
    container_id   TEXT        NOT NULL,
    message_id     BIGINT      NOT NULL,
    position       BIGSERIAL,
    pinned_by      UUID        NOT NULL,
    pinned_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (container_kind, container_id, message_id)
);
CREATE INDEX IF NOT EXISTS idx_pin_items_container ON pin_items (container_kind, container_id, position DESC);

CREATE TABLE IF NOT EXISTS pin_titles (
    container_kind TEXT        NOT NULL,
    container_id   TEXT        NOT NULL,
    title          TEXT        NOT NULL,
    set_by         UUID        NOT NULL,
    set_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (container_kind, container_id)
);

INSERT INTO pin_items (container_kind, container_id, message_id, pinned_by, pinned_at)
SELECT container_kind, container_id, message_id, pinned_by, pinned_at FROM pins
ON CONFLICT DO NOTHING;
