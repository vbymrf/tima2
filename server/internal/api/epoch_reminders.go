package api

import (
	"context"
	"log"
	"time"

	"tima/server/internal/escrow"
	"tima/server/internal/store"
)

// staleGroupsBatch — сколько групп обходим за проход. Остальные — следующим часом.
const staleGroupsBatch = 500

// remindStaleGroups — плановая смена ключа групп (ADR-0017 §3; ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ, часть 3).
//
// Напоминание на сообщение (remindAboutRotation) приходит, только когда в группе пишут, — а
// тихая группа как раз та, где ключ так и остаётся на прошлой эпохе. Здесь — то же
// напоминание по расписанию. **Не чаще раза в сутки на группу и эпоху:** клиенты, бывшие
// офлайн, увидят событие при подключении, а копить их в журнале событий по одному в час
// незачем.
func remindStaleGroups(ctx context.Context, st *store.Store, deps groupsDeps) int {
	epoch := escrow.EpochOf(time.Now())
	groups, err := st.GroupsWithStaleEpoch(ctx, epoch, staleGroupsBatch)
	if err != nil {
		log.Printf("плановая смена ключа: группы: %v", err)
		return 0
	}
	n := 0
	for _, g := range groups {
		if l := deps.limiter(); l != nil {
			ok, _, err := l.Allow(ctx, "gk_epoch_sched:"+g+":"+epoch, 1, 24*time.Hour)
			if err != nil || !ok {
				continue
			}
		}
		devices, err := st.ActiveMemberDevices(ctx, g, "")
		if err != nil || len(devices) == 0 {
			continue
		}
		remindAboutRotation(deps, ctx, g, devices)
		n++
	}
	if n > 0 {
		log.Printf("плановая смена ключа: эпоха %s, напомнили %d группам", epoch, n)
	}
	return n
}

// runEpochReminders — remindStaleGroups сразу и далее по интервалу до отмены ctx.
func runEpochReminders(ctx context.Context, st *store.Store, deps groupsDeps, every time.Duration) {
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		remindStaleGroups(ctx, st, deps)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}
