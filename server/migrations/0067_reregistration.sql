-- 0067: перерегистрация при компрометации и удаление личности
-- (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ9, ДУ11; Р34, Р37, Р39, Р50, Р51; устройство — §2г).
--
-- Процесс: С — прежняя личность, Н — заведённая перерегистрацией. Ожидание, затем окно
-- подтверждения; исход решает сервер по тому, кто подтвердил за окно. Подтвердили оба —
-- новое ожидание и новое окно (round + 1), без предела. Открытый процесс у аккаунта один.
CREATE TABLE IF NOT EXISTS reregistrations (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id        UUID        NOT NULL REFERENCES persons(person_id) ON DELETE CASCADE,
    old_user_id      UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    new_user_id      UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    window_from      TIMESTAMPTZ NOT NULL,
    window_to        TIMESTAMPTZ NOT NULL CHECK (window_to > window_from),
    -- «Аккаунт украден» от С: спор. До исхода заверять и заводить личности нельзя.
    claim_at         TIMESTAMPTZ,
    new_confirmed_at TIMESTAMPTZ,
    old_confirmed_at TIMESTAMPTZ,
    round            INT         NOT NULL DEFAULT 0,
    -- Извещение «окно открылось» ушло для этого круга (−1 — ещё ни для какого).
    window_noticed   INT         NOT NULL DEFAULT -1,
    outcome          TEXT        CHECK (outcome IN ('new', 'old')),
    closed_at        TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_reregistrations_open
    ON reregistrations (person_id) WHERE closed_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_reregistrations_due
    ON reregistrations (window_to) WHERE closed_at IS NULL;

-- Удаление личности (Р37, Р39): устройства отключаются сразу, личность живёт до delete_at
-- (членство в группах — тоже), затем deleted_at и выход из групп. Аккаунт и номер остаются.
ALTER TABLE users ADD COLUMN IF NOT EXISTS delete_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_delete_due ON users (delete_at) WHERE deleted_at IS NULL AND delete_at IS NOT NULL;

-- Почему устройство отключено: экран отключения говорит человеку причину (тексты §2б).
ALTER TABLE devices ADD COLUMN IF NOT EXISTS revoked_reason TEXT NOT NULL DEFAULT '';
