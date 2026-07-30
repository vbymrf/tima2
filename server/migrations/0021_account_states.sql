-- 0021: состояния аккаунта и сроки как данные (ПЛАН-РЕФАКТОРИНГА.md Р4,
-- ДОКУМЕНТАЦИЯ/04-данные-и-удаление).
--
-- Удаление в два шага: пометка (данные на месте, доступа нет) и физическое
-- стирание. Разнесены не из лени: помеченная запись уже недоступна ни владельцу,
-- ни собеседникам, но может понадобиться по юридически обязывающему запросу в
-- пределах установленного срока.

ALTER TABLE persons
    -- temporary — без телефона и почты, восстановления нет, удаляется по неактивности
    -- permanent — подтверждён, по неактивности НЕ удаляется никогда
    -- archived  — помечен удалённым; данные ещё есть, доступа уже нет
    ADD COLUMN IF NOT EXISTS state TEXT NOT NULL DEFAULT 'temporary'
        CHECK (state IN ('temporary', 'permanent', 'archived')),
    ADD COLUMN IF NOT EXISTS deleted_at     TIMESTAMPTZ,  -- момент пометки
    ADD COLUMN IF NOT EXISTS purge_after    TIMESTAMPTZ,  -- не раньше этого стираем
    ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Уникальность номера — только среди НЕархивных.
-- Архивный аккаунт продолжает хранить свой номер до физического стирания. Проверяй
-- уникальность по всем строкам подряд — и перерегистрация на тот же номер окажется
-- заблокированной на всю выдержку, то есть до полугода (ДОКУМЕНТАЦИЯ/04 §5).
DROP INDEX IF EXISTS idx_persons_phone_bidx;
CREATE UNIQUE INDEX IF NOT EXISTS idx_persons_phone_bidx ON persons(phone_bidx)
    WHERE phone_bidx IS NOT NULL AND state <> 'archived';

CREATE INDEX IF NOT EXISTS idx_persons_purge ON persons(purge_after) WHERE purge_after IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_persons_inactive ON persons(last_active_at) WHERE state = 'temporary';

-- Сроки как ДАННЫЕ, а не константы в коде: «если что изменится» должно быть
-- правкой строки, а не пересборкой и выкаткой.
CREATE TABLE IF NOT EXISTS retention_policy (
    name        TEXT      PRIMARY KEY,
    days        INT       NOT NULL CHECK (days >= 0),
    description TEXT      NOT NULL DEFAULT '',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO retention_policy (name, days, description) VALUES
    ('account_inactive_days', 30,
     'У: неактивность временного аккаунта → архив. Постоянных НЕ касается'),
    ('account_purge_days',    30,
     'СУ: архив аккаунта → физическое стирание'),
    ('reunion_window_days',   30,
     'К: окно воссоединения найденной истории с новым аккаунтом; обязано быть ≤ СУ'),
    ('chat_archive_days',    180,
     'Н: чат в архиве → пометка удалённым'),
    ('chat_purge_days',      180,
     'СЧ: пометка чата → физическое стирание'),
    ('legal_retention_days', 180,
     'Срок хранения по закону: столько живёт escrow-ключ сверх конца своей эпохи')
ON CONFLICT (name) DO NOTHING;

-- Стирание содержимого обнуляет и escrow-ct: после уничтожения ключа эпохи он
-- бесполезен, а занимает 1088 байт на каждое сообщение. Ослабляем ограничение до
-- «правильный размер ИЛИ пусто»; имя ограничения ищем, а не угадываем.
DO $$
DECLARE c TEXT;
BEGIN
    SELECT conname INTO c FROM pg_constraint
    WHERE conrelid = 'personal_messages'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%escrow_mlkem_ct%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE personal_messages DROP CONSTRAINT %I', c);
    END IF;
END $$;

ALTER TABLE personal_messages
    ADD CONSTRAINT personal_messages_escrow_ct_len
    CHECK (octet_length(escrow_mlkem_ct) IN (0, 1088));
