-- 0017: персональные данные в покое — шифрование + слепой индекс.
-- Модель угроз: утечка базы или бэкапа (ДОКУМЕНТАЦИЯ/04-данные-и-удаление §1).
-- Ключ и pepper лежат ФАЙЛОМ вне PostgreSQL (internal/pii), поэтому дамп базы
-- телефонов не содержит: в нём только HMAC для поиска и шифртекст.
--
-- Переход в два релиза: здесь добавляем колонки и снимаем NOT NULL, открытый
-- текст пока остаётся. Backfill выполняется кодом при старте (нужен ключ, в SQL
-- его нет). Удаление колонок phone/display_name — миграцией 0018 СЛЕДУЮЩЕГО
-- релиза: миграции применяются до backfill, и дропнуть их сейчас — потерять данные.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS phone_bidx BYTEA,   -- HMAC-SHA256(pepper, E.164): поиск без хранения номера
    ADD COLUMN IF NOT EXISTS phone_enc  BYTEA,   -- key_id ‖ nonce ‖ box
    ADD COLUMN IF NOT EXISTS name_enc   BYTEA;

-- Новые пользователи заводятся уже без открытого номера.
ALTER TABLE users ALTER COLUMN phone DROP NOT NULL;

-- Уникальность номера. Индекс частичный: строки без индекса (ещё не backfill-нутые)
-- друг другу не мешают.
-- ФАЗА 4: условие станет `WHERE deleted_at IS NULL` — удалённый аккаунт не должен
-- держать номер занятым, иначе перерегистрация заблокирована на весь срок выдержки
-- (ДОКУМЕНТАЦИЯ/04-данные-и-удаление §5).
CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone_bidx ON users(phone_bidx)
    WHERE phone_bidx IS NOT NULL;

-- SMS-коды: номер нужен обратно при проверке кода (выдача register-токена),
-- поэтому храним и шифртекст, а не только индекс.
ALTER TABLE sms_codes
    ADD COLUMN IF NOT EXISTS phone_bidx BYTEA,
    ADD COLUMN IF NOT EXISTS phone_enc  BYTEA;
ALTER TABLE sms_codes ALTER COLUMN phone DROP NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sms_codes_bidx ON sms_codes(phone_bidx, created_at DESC);
