package api

// Интеграционный тест GC (internal/worker): ретеншен событий и обёрток,
// окно апелляции wrapped_GK, sync.gap для устаревшего cursor.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"testing"
	"time"

	"tima/server/internal/worker"
)

func TestWorkerGCAndSyncGap(t *testing.T) {
	ts, srv := setupWithEvents(t)
	sender := registerDevice(t, ts, "+79997770001")
	recipient := registerDevice(t, ts, "+79997770002")

	// Два конверта → два события в логе получателя
	env1 := sealEnvelope(t, sender, []*device{recipient}, 4001, []byte("до GC"))
	resp := post(t, ts, env1, sender.token, "eeeeeeee-0000-0000-0000-000000004001")
	resp.Body.Close()
	env2 := sealEnvelope(t, sender, []*device{recipient}, 4002, []byte("тоже до GC"))
	resp = post(t, ts, env2, sender.token, "eeeeeeee-0000-0000-0000-000000004002")
	resp.Body.Close()

	conn := dialWS(t, ts, recipient.token)
	events, next, _ := pull(t, conn, 0)
	if len(events) != 2 {
		t.Fatalf("до GC: %d событий, ожидалось 2", len(events))
	}
	var ev1 int64
	_ = json.Unmarshal(events[0]["event_id"], &ev1)

	// Группа: v1 всем, исключение outcast, v2 без него — материал для окна апелляции
	outcast := registerDevice(t, ts, "+79997770003")
	groupID := createGroupAPI(t, ts, sender.token)
	addMemberAPI(t, ts, sender.token, groupID, recipient.userID, "member")
	addMemberAPI(t, ts, sender.token, groupID, outcast.userID, "member")
	if _, code := doRotate(t, ts, sender.token, groupID, 1, "periodic", []*device{sender, recipient, outcast}); code != http.StatusCreated {
		t.Fatal("ротация v1")
	}
	if code := authedJSON(t, ts, "DELETE", "/api/v1/groups/"+groupID+"/members/"+outcast.userID,
		sender.token, nil, nil); code != http.StatusNoContent {
		t.Fatal("исключение outcast")
	}
	if _, code := doRotate(t, ts, sender.token, groupID, 2, "member_leave", []*device{sender, recipient}); code != http.StatusCreated {
		t.Fatal("ротация v2")
	}

	// Окно апелляции отдельно: удаляются только ключи вышедшего
	if _, err := srv.Store.GCExcludedGroupKeys(context.Background(), 0); err != nil {
		t.Fatal(err)
	}
	if keys := fetchGroupKeys(t, ts, outcast.token, groupID, 0); len(keys) != 0 {
		t.Fatalf("после окна апелляции у outcast %d ключей, ожидалось 0", len(keys))
	}
	if keys := fetchGroupKeys(t, ts, recipient.token, groupID, 0); len(keys) != 2 {
		t.Fatalf("активный участник должен сохранить 2 версии, есть %d", len(keys))
	}

	// Полный GC с нулевым ретеншеном — «всё старше сейчас»
	w := &worker.Worker{Store: srv.Store, Retention: 0, AppealWindow: 0}
	if err := w.RunOnce(context.Background()); err != nil {
		t.Fatal(err)
	}

	// История чата пуста: обёртки удалены, конверт (escrow) остаётся до архива
	histReq, _ := http.NewRequest("GET", fmt.Sprintf("%s/api/v1/chats/%s/messages", ts.URL, chatID), nil)
	histReq.Header.Set("Authorization", "Bearer "+recipient.token)
	histResp, err := http.DefaultClient.Do(histReq)
	if err != nil {
		t.Fatal(err)
	}
	var hist struct {
		Messages []json.RawMessage `json:"messages"`
	}
	_ = json.NewDecoder(histResp.Body).Decode(&hist)
	histResp.Body.Close()
	if len(hist.Messages) != 0 {
		t.Fatalf("после GC в истории %d сообщений, ожидалось 0", len(hist.Messages))
	}
	// Групповые обёртки удалены и у активного участника (ретеншен 0)
	if keys := fetchGroupKeys(t, ts, recipient.token, groupID, 0); len(keys) != 0 {
		t.Fatalf("после GC у участника %d ключей, ожидалось 0", len(keys))
	}

	// sync.gap: cursor указывает до удалённых событий → полный re-bootstrap.
	// Live-кадры ротаций, накопившиеся в соединении, пропускаем.
	wsSend(t, conn, map[string]any{"event": "sync.pull", "cursor": ev1})
	var gap struct {
		Event      string `json:"event"`
		NextCursor int64  `json:"next_cursor"`
	}
	for gap.Event != "sync.gap" {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		_, raw, err := conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("sync.gap не пришёл: %v", err)
		}
		if json.Unmarshal(raw, &gap) == nil && (gap.Event == "sync.done" || gap.Event == "error") {
			t.Fatalf("ожидался sync.gap, получено %q", raw)
		}
	}
	if gap.NextCursor < next {
		t.Fatalf("next_cursor %d меньше последнего события %d", gap.NextCursor, next)
	}
	// С next_cursor из gap — обычный пустой догон, без повторного gap
	events, _, _ = pull(t, conn, gap.NextCursor)
	if len(events) != 0 {
		t.Fatalf("после re-bootstrap %d событий, ожидалось 0", len(events))
	}
	// cursor=0 (bootstrap) не считается разрывом
	if events, _, _ = pull(t, conn, 0); len(events) != 0 {
		t.Fatalf("bootstrap-pull: %d событий, ожидалось 0", len(events))
	}
}
