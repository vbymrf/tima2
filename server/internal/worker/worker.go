// Package worker — фоновые задачи монолита tima (подкоманда worker).
// Сейчас: GC ретеншена (правила — migrations/0008_gc.sql). Очередь push
// (Redis Stream → FCM/APNs) — когда появится push-провайдер; GC медиа —
// вместе со связью media↔message.
package worker

import (
	"context"
	"fmt"
	"log"
	"time"

	"tima/server/internal/store"
)

type Worker struct {
	Store *store.Store
	// Retention и AppealWindow — ЗАПАС, а не источник истины. С миграции 0030
	// сроки берутся из retention_policy: смена требования должна быть правкой
	// строки в базе, а не перевыкаткой. Эти поля работают только когда строки
	// политики нет — то есть на базе, где 0030 не применена.
	Retention    time.Duration // обёртки ключей и журнал событий: 90 дней (sync-offline.md §1)
	AppealWindow time.Duration // wrapped_GK исключённых: 30 дней (crypto-protocol §4.2)
}

// wholeDays — длительность в целых сутках. Сроки задаются днями и в переменных
// окружения, и в retention_policy; дробных суток здесь не бывает.
func wholeDays(d time.Duration) int { return int(d / (24 * time.Hour)) }

// retentionSeconds — сроки уборки в секундах, из таблицы политик.
func (w *Worker) retentionSeconds(ctx context.Context) (retention, window int64, err error) {
	rd, err := w.Store.RetentionDaysOr(ctx, "delivery_retention_days", wholeDays(w.Retention))
	if err != nil {
		return 0, 0, fmt.Errorf("политика delivery_retention_days: %w", err)
	}
	wd, err := w.Store.RetentionDaysOr(ctx, "appeal_window_days", wholeDays(w.AppealWindow))
	if err != nil {
		return 0, 0, fmt.Errorf("политика appeal_window_days: %w", err)
	}
	const day = int64(24 * 60 * 60)
	return int64(rd) * day, int64(wd) * day, nil
}

// RunOnce прогоняет все GC-задачи один раз; ошибки задач не прерывают остальные.
func (w *Worker) RunOnce(ctx context.Context) error {
	retention, window, err := w.retentionSeconds(ctx)
	if err != nil {
		return err
	}

	type job struct {
		name string
		run  func() (int64, error)
	}
	jobs := []job{
		{"device_events", func() (int64, error) { return w.Store.GCDeviceEvents(ctx, retention) }},
		{"personal_wrapped_keys", func() (int64, error) { return w.Store.GCPersonalWrappedKeys(ctx, retention) }},
		{"group_wrapped_keys", func() (int64, error) { return w.Store.GCGroupWrappedKeys(ctx, retention) }},
		{"excluded_group_keys", func() (int64, error) { return w.Store.GCExcludedGroupKeys(ctx, window) }},
		{"sms_codes", func() (int64, error) { return w.Store.GCExpiredSmsCodes(ctx) }},
		{"device_link_sessions", func() (int64, error) { return w.Store.GCExpiredLinkSessions(ctx) }},
		// Стирание содержимого сообщений, чьи ключи эпох уже уничтожены анклавом.
		// Метаданные строки остаются: у них отдельный срок — они не удаляются
		// никогда (ПЛАН-РЕФАКТОРИНГА.md §0).
		{"message_content", func() (int64, error) {
			return w.Store.PurgeMessageContent(ctx, time.Now(), purgeBatch)
		}},
		// Временные аккаунты, молчавшие дольше срока, уходят в архив. Постоянных
		// это не касается: они по неактивности не удаляются никогда.
		{"inactive_temporary", func() (int64, error) { return w.archiveInactive(ctx) }},
	}
	var firstErr error
	for _, j := range jobs {
		n, err := j.run()
		if err != nil {
			log.Printf("gc %s: %v", j.name, err)
			if firstErr == nil {
				firstErr = fmt.Errorf("gc %s: %w", j.name, err)
			}
			continue
		}
		if n > 0 {
			log.Printf("gc %s: удалено %d", j.name, n)
		}
	}
	return firstErr
}

// Run — RunOnce сразу и далее по интервалу до отмены ctx.
func (w *Worker) Run(ctx context.Context, interval time.Duration) {
	// Печатаем то, что будет применено, а не поля структуры: с 0030 сроки берутся
	// из базы, и поля могут с ними расходиться.
	if rs, ws, err := w.retentionSeconds(ctx); err == nil {
		log.Printf("worker: GC каждые %s (ретеншен %d дн., окно апелляции %d дн.)",
			interval, rs/86400, ws/86400)
	} else {
		log.Printf("worker: GC каждые %s (сроки прочитать не удалось: %v)", interval, err)
	}
	if err := w.RunOnce(ctx); err != nil {
		log.Printf("worker: %v", err)
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			log.Print("worker: остановлен")
			return
		case <-t.C:
			if err := w.RunOnce(ctx); err != nil {
				log.Printf("worker: %v", err)
			}
		}
	}
}

// purgeBatch — сколько строк стираем за проход. Ограничение намеренное: стирание
// идёт в фоне рядом с боевой нагрузкой, и длинная транзакция здесь никому не нужна.
const purgeBatch = 500

// archiveInactive переводит в архив временные аккаунты, молчавшие дольше срока.
// Сроки берутся из таблицы политик, а не из констант: «если что изменится» должно
// быть правкой строки в базе, а не пересборкой.
func (w *Worker) archiveInactive(ctx context.Context) (int64, error) {
	inactiveDays, err := w.Store.RetentionDays(ctx, "account_inactive_days")
	if err != nil {
		return 0, err
	}
	purgeDays, err := w.Store.RetentionDays(ctx, "account_purge_days")
	if err != nil {
		return 0, err
	}
	ids, err := w.Store.InactiveTemporaryAccounts(ctx, inactiveDays, purgeBatch)
	if err != nil {
		return 0, err
	}
	var n int64
	for _, id := range ids {
		if err := w.Store.MarkAccountDeleted(ctx, id, purgeDays); err != nil {
			return n, err
		}
		n++
	}
	return n, nil
}
