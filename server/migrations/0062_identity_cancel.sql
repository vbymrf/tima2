-- 0062: «Начать заново» и её отмена (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ6, Р27–Р31).
--
-- «Начать заново» по номеру заводит новую личность в том же аккаунте. Прежнее заверенное
-- устройство может её отменить, подтвердив фразой: новая личность выходит из цепочки, её
-- устройства отзываются, прежняя снова текущая. Отменённая личность не удаляется строкой —
-- её сообщения остаются у собеседников с пометкой (Р30), а подпись автора ссылается на неё.
ALTER TABLE users ADD COLUMN IF NOT EXISTS cancelled_at TIMESTAMPTZ;

-- Заявка новой личности на место прежней в группе (Р9, Р14). Новая личность не участник,
-- пока владелец или модератор не подтвердит: без доказательства фразой это может быть вор с
-- перевыпущенной SIM. Подтверждение делает её участником, прежнюю — вышедшей, и требует смены
-- ключа группы.
CREATE TABLE IF NOT EXISTS identity_group_claims (
    group_id     UUID        NOT NULL,
    user_id      UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    from_user_id UUID        NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    role         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);
