-- 0056: звонки отдельно от журнала сообщений — своя переменная `cts`.
-- План: doc_mig/ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md, ВЗ0а (решение заказчика 2026-09-26).
--
-- ── ЗАЧЕМ ────────────────────────────────────────────────────────────────────
--
-- С 0055 вызов ехал подсказкой `call.poke {call_id}` без номера события, и подтвердить
-- её было нечем: курсор журнала не двигался, а проверка «никто не забрал» смотрела
-- именно его. Любой звонок без ответа за 5 с звонящий видел как «не в сети».
--
-- Подтверждать подсказку номером журнала нельзя: `ack` сдвинул бы курсор через ещё не
-- забранные сообщения. Поэтому у звонков своя лента и свой курсор.
--
-- ── УСТРОЙСТВО ───────────────────────────────────────────────────────────────
--
--   call_tops     — вершина ленты человека: последний выданный `cts`.
--   call_updates  — изменения звонков человека по номеру: начат, доставлен, ответили…
--                   Живёт сутки (воркер): дольше — «разрыв», и пропущенные телефон
--                   берёт из журнала звонков.
--   call_cursors  — докуда устройство подтвердило ленту (`call.ack`).
--   calls.delivered_at — когда вызов впервые подтвердило устройство собеседника:
--                   по нему звонящему «Звонит» или «не в сети».
--
-- Всё добавляется; старые таблицы не трогаются. Откат — новый код просто не зовут.

CREATE TABLE IF NOT EXISTS call_tops (
    user_id UUID   PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    cts     BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS call_updates (
    user_id    UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    cts        BIGINT      NOT NULL,
    call_id    UUID        NOT NULL,
    -- ringing | delivered | unreachable | answered | declined | cancelled | ended |
    -- missed | busy | seen. Слово, а не код: читается в журнале сервера и в отчёте.
    change     TEXT        NOT NULL,
    -- Устройство, к которому изменение относится: у `answered` — ответившее. Пусто —
    -- ни к какому. По нему ответ ленты говорит спрашивающему «взяли здесь / не здесь».
    device_id  UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, cts)
);

CREATE INDEX IF NOT EXISTS idx_call_updates_created ON call_updates(created_at);
CREATE INDEX IF NOT EXISTS idx_call_updates_call ON call_updates(call_id);

CREATE TABLE IF NOT EXISTS call_cursors (
    device_id  UUID        PRIMARY KEY,
    cts        BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE calls
    ADD COLUMN IF NOT EXISTS delivered_at TIMESTAMPTZ;

COMMENT ON COLUMN calls.delivered_at IS
    'Когда вызов впервые подтвердило устройство собеседника (call.ack). NULL — не подтвердило';
