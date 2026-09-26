// Package worker — фоновые задачи монолита tima (подкоманда worker).
// Сейчас: GC ретеншена (правила — migrations/0008_gc.sql). Очередь push
// (Redis Stream → FCM/APNs) — когда появится push-провайдер; GC медиа —
// вместе со связью media↔message.
package worker

import (
	"context"
	"fmt"
	"log"
	"tima/server/internal/calls"
	"time"

	"tima/server/internal/store"
)

type Worker struct {
	Store *store.Store
	// Rooms — спросить LiveKit, жива ли комната. `nil` — LiveKit не настроен, и
	// брошенные звонки тогда не закрываются: закрывать их по одному лишь возрасту
	// значило бы однажды оборвать живой разговор.
	Rooms *calls.RoomClient
	// Retention и AppealWindow — ЗАПАС, а не источник истины. С миграции 0030
	// сроки берутся из retention_policy: смена требования должна быть правкой
	// строки в базе, а не перевыкаткой. Эти поля работают только когда строки
	// политики нет — то есть на базе, где 0030 не применена.
	Retention    time.Duration // обёртки ключей и журнал событий: 90 дней (sync-offline.md §1)
	AppealWindow time.Duration // wrapped_GK исключённых: 30 дней (crypto-protocol §4.2)
}

// wholeDays — длительность в целых сутках. Сроки задаются днями и в переменных
// окружения, и в retention_policy; дробных суток здесь не бывает.
// Сколько живёт лента звонков (0056). Не настройка: срок решён заказчиком, и довод за
// него — вторая дорога через журнал звонков работает каждый понедельник, а не раз в год.
const callUpdatesKept = 24 * time.Hour

func wholeDays(d time.Duration) int { return int(d / (24 * time.Hour)) }

// retentionSeconds — сроки уборки в секундах, из таблицы политик.
//
// Журнал звонков идёт отдельным сроком, а не общим (Ж5, 0054), и по умолчанию этот
// срок — **ноль, то есть «не убирать»**: строка звонка это одни метаданные, а они не
// удаляются никогда (`store.PurgeMessageContent`).
//
// Запасное значение — срок доставки, а не ноль. База без 0054 ведёт себя как прежде;
// подставить туда ноль значило бы сменить поведение старой базы правкой кода, а не
// миграцией.
func (w *Worker) retentionSeconds(ctx context.Context) (retention, window, journal int64, err error) {
	rd, err := w.Store.RetentionDaysOr(ctx, "delivery_retention_days", wholeDays(w.Retention))
	if err != nil {
		return 0, 0, 0, fmt.Errorf("политика delivery_retention_days: %w", err)
	}
	wd, err := w.Store.RetentionDaysOr(ctx, "appeal_window_days", wholeDays(w.AppealWindow))
	if err != nil {
		return 0, 0, 0, fmt.Errorf("политика appeal_window_days: %w", err)
	}
	jd, err := w.Store.RetentionDaysOr(ctx, "calls_journal_days", rd)
	if err != nil {
		return 0, 0, 0, fmt.Errorf("политика calls_journal_days: %w", err)
	}
	const day = int64(24 * 60 * 60)
	return int64(rd) * day, int64(wd) * day, int64(jd) * day, nil
}

// RunOnce прогоняет все GC-задачи один раз; ошибки задач не прерывают остальные.
func (w *Worker) RunOnce(ctx context.Context) error {
	retention, window, journal, err := w.retentionSeconds(ctx)
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
		// Брошенные звонки: строка открыта, а комнаты уже нет. См. closeAbandonedCalls.
		{"abandoned_calls", func() (int64, error) { return w.closeAbandonedCalls(ctx) }},
		// Лента звонков живёт сутки (решение заказчика 2026-09-26, ВЗ0а): телефон,
		// пропадавший дольше, получит «разрыв» и возьмёт пропущенные из журнала звонков.
		{"call_updates", func() (int64, error) { return w.Store.GCCallUpdates(ctx, callUpdatesKept) }},
		// Уборка законченных звонков — только если срок задан. Ноль означает «не
		// удалять», и передать его дальше нельзя: GCCalls(0) снёс бы всё, что
		// кончилось больше нуля секунд назад, то есть весь журнал разом.
		{"calls", func() (int64, error) {
			if journal <= 0 {
				return 0, nil
			}
			return w.Store.GCCalls(ctx, journal)
		}},
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
	if rs, ws, js, err := w.retentionSeconds(ctx); err == nil {
		log.Printf("worker: GC каждые %s (ретеншен %d дн., окно апелляции %d дн., журнал звонков %d дн.)",
			interval, rs/86400, ws/86400, js/86400)
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

// Сколько звонок должен числиться идущим, прежде чем мы им заинтересуемся.
//
// Пять минут: настоящий вызов звонит сорок пять секунд, а настоящий разговор в эти пять
// минут уже держит живую комнату — и по ней же будет опознан как живой.
const abandonedAfter = int64(5 * 60)

// closeAbandonedCalls — закрыть звонки, которые числятся идущими, а комнаты у них нет.
//
// ── ПОЧЕМУ СПРАШИВАЕМ, А НЕ СЧИТАЕМ ПО ВОЗРАСТУ ────────────────────────────
//
// Возраст здесь только отбирает кандидатов. Закрывать по нему — значит назначить
// разговору предельную длину, а он вправе длиться часами. Комната знает правду: её нет,
// значит звонку неоткуда идти.
//
// **Ошибка обязана быть в сторону «жив».** LiveKit не ответил, сеть моргнула — строку не
// трогаем. Призрак, проживший лишний час, дешевле оборванного разговора.
func (w *Worker) closeAbandonedCalls(ctx context.Context) (int64, error) {
	if w.Rooms == nil {
		return 0, nil
	}
	open, err := w.Store.OpenCalls(ctx, abandonedAfter, 200)
	if err != nil {
		return 0, err
	}
	var closed int64
	for _, c := range open {
		alive, err := w.Rooms.RoomExists(ctx, c.Room)
		if err != nil || alive {
			continue
		}
		// ── ДВА РАЗНЫХ СЛУЧАЯ, И РАНЬШЕ ОНИ БЫЛИ ОДНИМ ──────────────────────
		//
		// Оба закрывались как `ended`, и для журнала это оказалось ложью — крупной.
		// `ended_at` ставится **в момент уборки**, а уборщик ходит раз в час
		// (TIMA_GC_INTERVAL) по звонкам старше пяти минут. Разговор на минуту, чей
		// `/end` не доехал, читался в журнале как час с лишним.
		//
		// `ringing` → `missed`: трубку не брали, `answered_at` пуст, длительности
		// нет вовсе — врать нечем. Это обычный пропущенный.
		//
		// `answered` → `lost`: разговор шёл, а конец неизвестен. Журнал такую
		// строку показывает как «оборвался» и длительность не рисует.
		//
		// Кто закончил — никто, поэтому `ended_by` пуст: звонок бросили.
		state := "lost"
		if c.State == "ringing" {
			state = "missed"
		}
		if err := w.Store.SetCallState(ctx, c.CallID, state, ""); err != nil {
			log.Printf("closeAbandonedCalls %s: %v", c.CallID, err)
			continue
		}
		closed++
	}
	return closed, nil
}
