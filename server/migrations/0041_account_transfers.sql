-- 0041: передача виртуального аккаунта (ПЛАН-КОНТАКТОВ.md, Д12).
--
-- Передача — не одно действие, а четыре, и все обязательные: отзыв всех устройств
-- прежнего владельца, ротация групповых ключей, гашение бэкапа под фразу и только потом
-- перевязка владельца. Смена одного поля прежнего владельца не отрезает: он знал фразу и
-- держал устройства.
--
-- Здесь — состояние самой передачи: код, срок, попытки.
CREATE TABLE IF NOT EXISTS account_transfers (
    transfer_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Кого передают и кто передаёт. Оба — аккаунты (persons), а не личности:
    -- передача принадлежит аккаунту и переживает смену личности.
    person_id    UUID        NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    from_person  UUID        NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    -- Хэш кода передачи, а не сам код: утечка таблицы не должна отдавать коды, как и
    -- у sms_codes (0002). Код видит только тот, кому его показали.
    code_hash    BYTEA       NOT NULL,
    -- Тридцать минут от подтверждения телефона на старом устройстве (решение заказчика
    -- 2026-09-05). Считает СЕРВЕР: на клиенте этот срок переводится часами телефона.
    expires_at   TIMESTAMPTZ NOT NULL,
    -- Неверные фразы. Третья гасит код: перебор упирается не в длину фразы, а в двух
    -- живых людей — одному приходит SMS на каждую попытку, второй обязан выдать новый
    -- код после каждой третьей.
    attempts     SMALLINT    NOT NULL DEFAULT 0,
    -- Погашен: предъявлен и принят, либо отменён владельцем, либо сожжён попытками.
    closed_at    TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Одна живая передача на аккаунт: две одновременные означали бы, что аккаунт обещан
-- двоим, и получит его тот, кто быстрее.
CREATE UNIQUE INDEX IF NOT EXISTS idx_transfers_alive
    ON account_transfers (person_id) WHERE closed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_transfers_code ON account_transfers (code_hash) WHERE closed_at IS NULL;
