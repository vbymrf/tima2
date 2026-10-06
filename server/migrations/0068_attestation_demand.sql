-- 0068: ручка «этот телефон — пройти аттестацию» (ПЛАН-(ЗБ)-ЗАЩИТЫ-ОТ-БОТОВ ЗБ1).
--
-- Требование ставится конкретному устройству. Пока после него нет свежей годной аттестации
-- (attested_at позже требования и состояние verified), сервер отказывает этому устройству в
-- действиях. Кто и когда ставит требование — решается вместе с системной защитой от ботов;
-- сейчас только ручка и совместимость клиента.
ALTER TABLE devices ADD COLUMN IF NOT EXISTS attestation_demanded_at   TIMESTAMPTZ;
ALTER TABLE devices ADD COLUMN IF NOT EXISTS attestation_demand_reason TEXT NOT NULL DEFAULT '';
