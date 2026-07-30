-- 0024: архив чата у каждого свой + срок жизни аккаунта без номера и почты.
--
-- ── Архив чата ──
--
-- У личных чатов нет таблицы: chat_id вычисляется из пары собеседников, хранить
-- пометку «в архиве» было негде. При этом архивирование — ЛИЧНОЕ действие, «убрать
-- с глаз». Неправильно, когда уборка одного человека назначает удаление переписки
-- другому: чат считается архивным для стирания только когда его убрали ВСЕ.

CREATE TABLE IF NOT EXISTS chat_states (
    chat_id     UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    archived_at TIMESTAMPTZ,          -- NULL = чат в обычном списке
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chat_id, user_id)
);

-- Поиск «кто ещё не убрал» и обход архивных при стирании.
CREATE INDEX IF NOT EXISTS idx_chat_states_archived ON chat_states(archived_at)
    WHERE archived_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_chat_states_user ON chat_states(user_id, chat_id);

-- ── Аккаунт без номера и почты ──
--
-- Такой аккаунт работает, но ОГРАНИЧЕННОЕ время: дальше вход только после привязки
-- телефона или почты. Это ДРУГОЙ срок, не «месяц неактивности»:
--   * неактивность (У, 30 дней) — человек молчит, аккаунт уходит в архив;
--   * срок без данных (90 дней) — человек может пользоваться, но обязан привязать
--     номер, иначе перестанет входить.
-- Они про разное и работают независимо.
--
-- Сюда же попадает тот, у кого номер забрал новый владелец (оператор перевыпустил):
-- аккаунт остаётся, переписка остаётся, но номер надо привязать новый.
ALTER TABLE persons
    ADD COLUMN IF NOT EXISTS credentials_due TIMESTAMPTZ;

COMMENT ON COLUMN persons.credentials_due IS
    'До этого момента можно входить без телефона и почты; после — только привязав их. NULL = данные есть.';

CREATE INDEX IF NOT EXISTS idx_persons_credentials_due ON persons(credentials_due)
    WHERE credentials_due IS NOT NULL;

INSERT INTO retention_policy (name, days, description) VALUES
    ('no_credentials_days', 90,
     'Сколько аккаунт живёт без телефона и почты. Дальше вход только после их привязки'),
    ('chat_all_archived_days', 180,
     'Н: чат, убранный в архив ВСЕМИ участниками → пометка удалённым')
ON CONFLICT (name) DO NOTHING;
