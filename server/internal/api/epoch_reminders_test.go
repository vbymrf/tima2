package api

import (
	"context"
	"testing"

	"tima/server/internal/ratelimit"
)

// Тихая группа с ключом прошлой эпохи получает напоминание по расписанию (ADR-0017 §3), а не
// только на первое сообщение. В тестах эпоха ключа неизвестна (реестр депозитария пуст) —
// значит, заведомо не текущая.
func TestQuietGroupGetsScheduledRotationReminder(t *testing.T) {
	ts, srv := setup(t)
	admin := registerDevice(t, ts, "+79990000311")
	member := registerDevice(t, ts, "+79990000312")
	g := createGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, g, member.userID, "member")
	if _, code := doRotate(t, ts, admin.token, g, 1, "periodic", []*device{admin, member}); code != 200 && code != 201 {
		t.Fatalf("ротация: %d", code)
	}
	before := countRotationEvents(t, srv, member.id)
	deps := groupsDeps{store: srv.Store, limiter: func() *ratelimit.Limiter { return srv.Limit }, notifier: srv.notifier()}
	if n := remindStaleGroups(context.Background(), srv.Store, deps); n < 1 {
		t.Fatalf("напомнили %d группам, ожидалась хотя бы одна", n)
	}
	if after := countRotationEvents(t, srv, member.id); after != before+1 {
		t.Fatalf("событий смены ключа у участника: было %d, стало %d", before, after)
	}
}
