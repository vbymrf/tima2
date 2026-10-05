-- 0063: закладка под аттестацию телефона (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ8, Р20–Р23).
--
-- Телефон заводит в защищённой части ключ P-256 с аттестацией и подписывает им ключи своего
-- устройства. Сервер в режиме «записывать» проверяет и пишет итог сюда; отказывать начнёт
-- режим «требовать», когда будет видно, какие телефоны и прошивки проходят.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS attestation_state TEXT NOT NULL DEFAULT '';  -- '' | verified | failed
ALTER TABLE devices ADD COLUMN IF NOT EXISTS attestation_info  TEXT NOT NULL DEFAULT '';  -- JSON: уровень, загрузка, приложение, корень
ALTER TABLE devices ADD COLUMN IF NOT EXISTS attested_at       TIMESTAMPTZ;
