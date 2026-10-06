-- 0069: смена SIM — смена номера аккаунта (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ9, Р34, Р40;
-- порядок — ответ заказчика 2026-10-06).
--
-- Заявка: фраза + код из SMS на прежний номер. Через срок ожидания открывается окно
-- подтверждения: фраза той же личности (она поменяться не может) + код из SMS на новый номер.
-- Подтвердили в окне — номер аккаунта меняется; не подтвердили — заявка гаснет, номер прежний.
-- Перерегистрация, запущенная во время заявки, её отменяет: подать заново можно после спора.
-- Открытая заявка у аккаунта одна.
CREATE TABLE IF NOT EXISTS phone_changes (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id      UUID        NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    -- Личность, подавшая заявку: подтверждает только она — фраза поменяться не может.
    user_id        UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    new_phone_bidx BYTEA       NOT NULL,
    new_phone_enc  BYTEA       NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    window_from    TIMESTAMPTZ NOT NULL,
    window_to      TIMESTAMPTZ NOT NULL CHECK (window_to > window_from),
    -- Извещение «окно открылось» ушло.
    window_noticed BOOLEAN     NOT NULL DEFAULT FALSE,
    outcome        TEXT        CHECK (outcome IN ('changed', 'expired', 'cancelled')),
    closed_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_phone_changes_open
    ON phone_changes (person_id) WHERE closed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_phone_changes_due
    ON phone_changes (window_from) WHERE closed_at IS NULL;
