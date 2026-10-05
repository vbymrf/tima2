-- 0064: запрет упрощённой перерегистрации (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ10, Р36, Р41).
--
-- «Начать заново» — упрощённая перерегистрация: новая личность по одной SMS, без фразы.
-- Владелец может её запретить на своём аккаунте (фраза и SMS), и запрет не снимается совсем:
-- его ставят ровно затем, чтобы укравший SIM не мог им воспользоваться, и снятие запрета
-- открыло бы ему ту же дверь. Поэтому защита — в самой базе, а не только в коде: триггер
-- не даёт вернуть столбец в false никаким запросом.
ALTER TABLE persons ADD COLUMN IF NOT EXISTS start_anew_banned BOOLEAN NOT NULL DEFAULT false;

CREATE OR REPLACE FUNCTION start_anew_ban_is_final() RETURNS trigger AS $$
BEGIN
    IF OLD.start_anew_banned AND NOT NEW.start_anew_banned THEN
        RAISE EXCEPTION 'запрет «Начать заново» не снимается (Р41)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS start_anew_ban_final ON persons;
CREATE TRIGGER start_anew_ban_final
    BEFORE UPDATE OF start_anew_banned ON persons
    FOR EACH ROW EXECUTE FUNCTION start_anew_ban_is_final();
