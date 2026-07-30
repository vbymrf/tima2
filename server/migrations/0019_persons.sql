-- 0019: аккаунт как цепочка идентификаторов (ПЛАН-РЕФАКТОРИНГА.md, решения 7 и 8).
--
-- Идентификатор автора входит в подпись сообщения, поэтому переписать его задним
-- числом невозможно: правка ломает доказательство авторства. Значит потеря ключей
-- или уход номера другому человеку не могут «переименовать» пользователя — они
-- заводят НОВЫЙ идентификатор, а прежние остаются жить вместе с их сообщениями.
--
-- Отсюда разделение:
--   persons — аккаунт (человек). Здесь живут телефон и профиль: они принадлежат
--             аккаунту целиком, а не отдельному идентификатору.
--   users   — личность в конкретный период. Здесь ключи и связь с цепочкой.
--
-- Состояния аккаунта (временный/постоянный/архивный) и сроки — этап Р4, не здесь.

CREATE TABLE IF NOT EXISTS persons (
    person_id  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_bidx BYTEA,      -- HMAC-SHA256(pepper, E.164); NULL — временный аккаунт без телефона
    phone_enc  BYTEA,      -- key_id ‖ nonce ‖ box
    name_enc   BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Уникальность номера. Частичный: аккаунты без телефона друг другу не мешают.
-- ФАЗА Р4: условие станет `WHERE phone_bidx IS NOT NULL AND deleted_at IS NULL` —
-- удалённый аккаунт не должен держать номер занятым (ДОКУМЕНТАЦИЯ/04 §5).
CREATE UNIQUE INDEX IF NOT EXISTS idx_persons_phone_bidx ON persons(phone_bidx)
    WHERE phone_bidx IS NOT NULL;

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS person_id   UUID REFERENCES persons(person_id),
    ADD COLUMN IF NOT EXISTS valid_from  TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS valid_to    TIMESTAMPTZ,   -- NULL = текущая личность аккаунта
    -- Чей ключ подписал связку. Нужен, чтобы проверяющий знал, ЧЕМ проверять подпись.
    ADD COLUMN IF NOT EXISTS linked_from UUID REFERENCES users(user_id),
    -- Подпись прежним ключом личности над связкой. NULL = связка административная:
    -- человек доказал владение номером, но не владение ключами. Собеседник обязан
    -- увидеть предупреждение о смене личности (ДОКУМЕНТАЦИЯ/02 §4, решение 8).
    ADD COLUMN IF NOT EXISTS link_proof  BYTEA;

-- Перенос существующих: каждому пользователю — свой аккаунт. Построчно, а не одним
-- INSERT ... SELECT: сопоставлять пришлось бы по phone_bidx, а он бывает NULL, и
-- NULL с NULL не сравнивается — часть строк осталась бы без аккаунта.
DO $$
DECLARE r RECORD; pid UUID;
BEGIN
    FOR r IN SELECT user_id, phone_bidx, phone_enc, name_enc, created_at FROM users WHERE person_id IS NULL LOOP
        INSERT INTO persons (phone_bidx, phone_enc, name_enc, created_at)
        VALUES (r.phone_bidx, r.phone_enc, r.name_enc, r.created_at)
        RETURNING person_id INTO pid;
        UPDATE users SET person_id = pid, valid_from = r.created_at WHERE user_id = r.user_id;
    END LOOP;
END $$;

ALTER TABLE users ALTER COLUMN person_id SET NOT NULL;

-- Персональные данные переехали в persons: у личности их больше нет.
DROP INDEX IF EXISTS idx_users_phone_bidx;
ALTER TABLE users
    DROP COLUMN IF EXISTS phone_bidx,
    DROP COLUMN IF EXISTS phone_enc,
    DROP COLUMN IF EXISTS name_enc;

-- У аккаунта ровно одна текущая личность. Без этого «текущий идентификатор»
-- перестаёт быть определённым понятием, и под каким писать — вопрос удачи.
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_current_per_person ON users(person_id)
    WHERE valid_to IS NULL;

CREATE INDEX IF NOT EXISTS idx_users_person ON users(person_id);
