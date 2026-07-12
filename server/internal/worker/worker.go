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
	Store        *store.Store
	Retention    time.Duration // конверты/события: 90 дней (sync-offline.md §1)
	AppealWindow time.Duration // wrapped_GK исключённых: 30 дней (crypto-protocol §4.2)
}

// RunOnce прогоняет все GC-задачи один раз; ошибки задач не прерывают остальные.
func (w *Worker) RunOnce(ctx context.Context) error {
	retention := int64(w.Retention / time.Second)
	window := int64(w.AppealWindow / time.Second)

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
	log.Printf("worker: GC каждые %s (ретеншен %s, окно апелляции %s)",
		interval, w.Retention, w.AppealWindow)
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
