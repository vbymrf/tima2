-- 0044: у принесённой записи появляется ВИД контейнера (ПЛАН-КАНАЛОВ К2, ADR-0019 §7).
--
-- Было: два поля адреса, и оба про группу — `ref_group_id` и `ref_message_id`. Пока
-- принести можно было только из группы, этого хватало. Появился второй источник — канал,
-- и правило «комментарий уходит туда, где лежит контейнер оригинала» требует знать вид
-- контейнера, а не только его номер.
--
-- Вторая пара полей (`ref_channel_id`) была бы третьей при третьем источнике, и каждая
-- выборка ветвилась бы по тому, какая пара заполнена. Поэтому адрес один: вид, контейнер,
-- запись.
ALTER TABLE channel_posts
    ADD COLUMN IF NOT EXISTS ref_kind         TEXT,
    ADD COLUMN IF NOT EXISTS ref_container_id UUID;

-- Перенос прежнего адреса в новый. Данных на стенде нет (Plan.md §0.0 решение 2 стирает
-- серверные данные), но миграция обязана быть верной и на непустой таблице: иначе она
-- верна только там, где её проверяли.
UPDATE channel_posts
   SET ref_kind = 'group', ref_container_id = ref_group_id
 WHERE ref_group_id IS NOT NULL AND ref_container_id IS NULL;

-- СТАРЫЕ КОЛОНКИ ОСТАЮТСЯ И НЕ ПИШУТСЯ. Правило аддитивности (ПРАВИЛА-РАБОТЫ §2): снятие
-- колонки идёт отдельным выпуском, следующим за тем, где код перестал её читать. С этого
-- среза код пишет и читает только `ref_kind`/`ref_container_id`; `ref_group_id` остаётся
-- пустым у новых строк и удаляется миграцией следующего выпуска.
--
-- Прежнее ограничение `chk_cp_ref_pair` ослабляется, а не снимается: оно требовало, чтобы
-- `ref_group_id` и `ref_message_id` были заполнены ОБА или ни одного, а у новой ссылки
-- заполнен только второй — номер записи теперь общий для обоих видов контейнера.
--
-- Ослабление, а не удаление, и не сужение: старый инвариант «адрес группы без номера
-- записи бессмыслен» остаётся под тем же именем. Само ограничение уйдёт вместе с
-- колонкой, в следующем выпуске.
ALTER TABLE channel_posts DROP CONSTRAINT IF EXISTS chk_cp_ref_pair;
ALTER TABLE channel_posts ADD CONSTRAINT chk_cp_ref_pair CHECK (
    ref_group_id IS NULL OR ref_message_id IS NOT NULL
);

-- Адрес целиком или его нет вовсе: половина ссылки — запись, ссылающаяся в никуда.
ALTER TABLE channel_posts DROP CONSTRAINT IF EXISTS chk_cp_ref_container;
ALTER TABLE channel_posts ADD CONSTRAINT chk_cp_ref_container CHECK (
    (ref_kind IS NULL AND ref_container_id IS NULL)
    OR (ref_kind IN ('group', 'channel') AND ref_container_id IS NOT NULL AND ref_message_id IS NOT NULL)
);

-- Одна и та же запись не приносится к себе дважды: вторая ссылка ничего не добавляет, а
-- на странице выглядит повтором. То же правило, что у `idx_cp_ref_once`, но по новому
-- адресу — и оно же теперь работает для канала.
CREATE UNIQUE INDEX IF NOT EXISTS idx_cp_ref_container_once
    ON channel_posts (channel_id, ref_kind, ref_container_id, ref_message_id)
    WHERE ref_container_id IS NOT NULL AND NOT deleted;
