-- Отметка разбора у отчёта о проблеме (заказчик 2026-11-20: «Разбери нерассмотренные отчёты… сделай отметку в базе
-- стенда»). Опись и разборы — doc_mig/ЖУРНАЛ-И-ОТЛАДКА/УЧЁТ-ОШИБОК/; здесь — та же отметка у самого отчёта, чтобы
-- учёт не расходился с документом и был виден без репозитория.
--
-- review_status: NULL — не рассмотрено; 'reviewed' — рассмотрено (причина найдена); 'check' — рассмотрено, нужна
-- проверка на свежей сборке; 'not_bug' — не поломка (сеть, проверочная отметка); 'stale' — устарело (старая сборка,
-- место переделано). review_ref — где разобрано (путь документа, коммит).
ALTER TABLE problem_reports ADD COLUMN IF NOT EXISTS review_status TEXT;
ALTER TABLE problem_reports ADD COLUMN IF NOT EXISTS review_note   TEXT;
ALTER TABLE problem_reports ADD COLUMN IF NOT EXISTS review_ref    TEXT;
ALTER TABLE problem_reports ADD COLUMN IF NOT EXISTS reviewed_at   TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_problem_reports_unreviewed ON problem_reports (created_at DESC) WHERE review_status IS NULL;
