-- 0020: реестр ключей escrow (ПЛАН-РЕФАКТОРИНГА.md Р2).
--
-- Приватные ключи живут ТОЛЬКО в анклаве. Здесь кэш публичных частей и, что важнее,
-- destroy_at: по нему этап Р4 решает, можно ли уже физически стирать содержимое
-- сообщений. Без этой колонки гейт стирания пришлось бы спрашивать у анклава на
-- каждую строку.
--
-- Запись появляется лениво — когда в чате в этой эпохе впервые что-то происходит.
-- Молчащие чаты ключей не имеют и места не занимают.

CREATE TABLE IF NOT EXISTS escrow_keys (
    -- Ровно то, что летит в EscrowBlob.escrow_key_version (uint32 в proto).
    id          BIGINT      PRIMARY KEY CHECK (id > 0 AND id <= 4294967295),
    region      TEXT        NOT NULL,   -- измерение заведено сразу, значение пока одно
    epoch       TEXT        NOT NULL,   -- 'YYYY-MM'
    chat_id     UUID        NOT NULL,
    public_key  BYTEA       NOT NULL CHECK (octet_length(public_key) = 1184),  -- ML-KEM-768
    valid_from  TIMESTAMPTZ NOT NULL,   -- окно, в котором на ключ шифруют
    valid_to    TIMESTAMPTZ NOT NULL,
    destroy_at  TIMESTAMPTZ NOT NULL,   -- после этого анклав стирает приватный ключ
    cached_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (region, epoch, chat_id)
);

-- Гейт стирания в Р4 идёт по сроку: «что уже пережило свой ключ».
CREATE INDEX IF NOT EXISTS idx_escrow_keys_destroy ON escrow_keys(destroy_at);
-- Выдача ключа клиенту — поиск по области.
CREATE INDEX IF NOT EXISTS idx_escrow_keys_scope ON escrow_keys(chat_id, epoch);
