package api

// Лента звонков — своя переменная `cts` (ПЛАН-ВХОДЯЩЕГО-ЗВОНКА.md, ВЗ0а).
//
// Проверяется то, ради чего лента заведена: «не в сети» говорится, только когда вызов
// действительно никто не подтвердил, подтверждение не трогает журнал сообщений, и
// звонок, который никто не закрыл, закрывает срок.

import (
	"context"
	"fmt"
	"net/http/httptest"
	"testing"
	"time"
)

type callUpdatesPage struct {
	Top     int64 `json:"top"`
	Gap     bool  `json:"gap"`
	Updates []struct {
		Cts    int64  `json:"cts"`
		CallID string `json:"call_id"`
		Change string `json:"change"`
		Here   bool   `json:"here"`
		Call   struct {
			State       string `json:"state"`
			InitiatorID string `json:"initiator_id"`
		} `json:"call"`
	} `json:"updates"`
}

func updatesOf(t *testing.T, ts *httptest.Server, token string, after int64) callUpdatesPage {
	t.Helper()
	var page callUpdatesPage
	if code := getAuthed(t, ts, token, fmt.Sprintf("/api/v1/calls/updates?after=%d", after), &page); code != 200 {
		t.Fatalf("calls/updates: %d", code)
	}
	return page
}

func changesOf(page callUpdatesPage, callID string) []string {
	var out []string
	for _, u := range page.Updates {
		if u.CallID == callID {
			out = append(out, u.Change)
		}
	}
	return out
}

func hasChange(page callUpdatesPage, callID, change string) bool {
	for _, c := range changesOf(page, callID) {
		if c == change {
			return true
		}
	}
	return false
}

// тихие сроки: проверки не ждут настоящих секунд, а повтор подсказки не мешает.
func quietCallTimers(t *testing.T, notice, deadline time.Duration) {
	wasNotice, wasRepoke, wasDeadline := deliveryNoticeAfter, ringRepokeAfter, ringDeadline
	deliveryNoticeAfter, ringRepokeAfter, ringDeadline = notice, nil, deadline
	t.Cleanup(func() { deliveryNoticeAfter, ringRepokeAfter, ringDeadline = wasNotice, wasRepoke, wasDeadline })
}

func startTestCall(t *testing.T, ts *httptest.Server, callerToken, peerID string) string {
	t.Helper()
	var start struct {
		CallID string `json:"call_id"`
	}
	if code := postAuthed(t, ts, callerToken, "POST", "/api/v1/calls",
		map[string]string{"peer_id": peerID, "kind": "video"}, &start); code != 201 {
		t.Fatalf("startCall: %d", code)
	}
	return start.CallID
}

func TestВызовЛожитсяВЛентуСобеседника(t *testing.T) {
	quietCallTimers(t, time.Hour, time.Hour)
	ts, srv := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000070")
	callee := registerDevice(t, ts, "+79990000071")

	callID := startTestCall(t, ts, caller.token, callee.userID)

	page := updatesOf(t, ts, callee.token, 0)
	if page.Top < 1 || page.Gap {
		t.Fatalf("вершина %d, разрыв %v — ожидалась лента без разрыва", page.Top, page.Gap)
	}
	if got := changesOf(page, callID); len(got) != 1 || got[0] != "ringing" {
		t.Fatalf("у собеседника изменения %v, ожидался один ringing", got)
	}
	if page.Updates[0].Call.State != "ringing" || page.Updates[0].Call.InitiatorID != caller.userID {
		t.Fatalf("снимок звонка неверен: %+v", page.Updates[0].Call)
	}
	// Журнал сообщений собеседника звонком не тронут — ради этого лента и заведена.
	if n := countEvents(t, srv, callee.id, "call.incoming", callID); n != 0 {
		t.Fatalf("вызов лёг в журнал устройства: %d", n)
	}
}

func TestПодтверждённыйВызовДоставлен(t *testing.T) {
	quietCallTimers(t, 300*time.Millisecond, time.Hour)
	ts, srv := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000072")
	callee := registerDevice(t, ts, "+79990000073")

	callID := startTestCall(t, ts, caller.token, callee.userID)
	top := updatesOf(t, ts, callee.token, 0).Top

	// Телефон собеседника применил ленту и подтвердил — то, что делает клиент по каналу.
	ackCalls(context.Background(), srv.Store, srv.notifier(), callee.userID, callee.id, top)

	time.Sleep(time.Second)

	page := updatesOf(t, ts, caller.token, 0)
	if !hasChange(page, callID, "delivered") {
		t.Fatalf("звонящему не сказали «доставлен»: %v", changesOf(page, callID))
	}
	if hasChange(page, callID, "unreachable") {
		t.Fatalf("доставленный вызов назван «не в сети»: %v", changesOf(page, callID))
	}
}

func TestНеподтверждённыйВызовНеВСетиНоЗвонокЖив(t *testing.T) {
	quietCallTimers(t, 200*time.Millisecond, time.Hour)
	ts, srv := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000074")
	callee := registerDevice(t, ts, "+79990000075")

	callID := startTestCall(t, ts, caller.token, callee.userID)
	time.Sleep(time.Second)

	if !hasChange(updatesOf(t, ts, caller.token, 0), callID, "unreachable") {
		t.Fatal("вызов никто не подтвердил, а звонящему не сказали «не в сети»")
	}
	call, err := srv.Store.GetCall(context.Background(), callID)
	if err != nil {
		t.Fatal(err)
	}
	if call.State != "ringing" {
		t.Fatalf("звонок стал %q — «не в сети» не кладёт трубку", call.State)
	}

	// Телефон вернулся и подтвердил — звонящему всё-таки «доставлен».
	top := updatesOf(t, ts, callee.token, 0).Top
	ackCalls(context.Background(), srv.Store, srv.notifier(), callee.userID, callee.id, top)
	if !hasChange(updatesOf(t, ts, caller.token, 0), callID, "delivered") {
		t.Fatal("поздно вернувшийся телефон не сделал вызов доставленным")
	}
}

func TestПодтверждениеВышеВершиныОтвергается(t *testing.T) {
	quietCallTimers(t, time.Hour, time.Hour)
	ts, srv := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000076")
	callee := registerDevice(t, ts, "+79990000077")

	callID := startTestCall(t, ts, caller.token, callee.userID)
	top := updatesOf(t, ts, callee.token, 0).Top

	// Номер соседнего аккаунта после переключения — выше вершины этого человека.
	ackCalls(context.Background(), srv.Store, srv.notifier(), callee.userID, callee.id, top+40)

	call, err := srv.Store.GetCall(context.Background(), callID)
	if err != nil {
		t.Fatal(err)
	}
	if !call.DeliveredAt.IsZero() {
		t.Fatal("чужой номер сделал вызов доставленным")
	}
}

func TestИсходыЗвонкаНазываютсяСловами(t *testing.T) {
	quietCallTimers(t, time.Hour, time.Hour)
	ts, _ := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000078")
	callee := registerDevice(t, ts, "+79990000079")

	// Отклонил собеседник.
	declined := startTestCall(t, ts, caller.token, callee.userID)
	if code := postAuthed(t, ts, callee.token, "POST", "/api/v1/calls/"+declined+"/end", nil, nil); code != 200 {
		t.Fatalf("end: %d", code)
	}
	// Занят.
	busy := startTestCall(t, ts, caller.token, callee.userID)
	if code := postAuthed(t, ts, callee.token, "POST", "/api/v1/calls/"+busy+"/end?reason=busy", nil, nil); code != 200 {
		t.Fatalf("end busy: %d", code)
	}
	// Звонящий передумал.
	cancelled := startTestCall(t, ts, caller.token, callee.userID)
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls/"+cancelled+"/end", nil, nil); code != 200 {
		t.Fatalf("end cancel: %d", code)
	}
	// Ответили и поговорили.
	talked := startTestCall(t, ts, caller.token, callee.userID)
	if code := postAuthed(t, ts, callee.token, "POST", "/api/v1/calls/"+talked+"/answer", nil, nil); code != 200 {
		t.Fatalf("answer: %d", code)
	}
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls/"+talked+"/end", nil, nil); code != 200 {
		t.Fatalf("end talked: %d", code)
	}

	callerPage := updatesOf(t, ts, caller.token, 0)
	calleePage := updatesOf(t, ts, callee.token, 0)
	for _, c := range []struct{ call, change string }{
		{declined, "declined"}, {busy, "busy"}, {cancelled, "cancelled"}, {talked, "ended"},
	} {
		if !hasChange(callerPage, c.call, c.change) || !hasChange(calleePage, c.call, c.change) {
			t.Fatalf("исход %s не дошёл до обеих сторон: у звонящего %v, у собеседника %v",
				c.change, changesOf(callerPage, c.call), changesOf(calleePage, c.call))
		}
	}
	// Ответ — «взяли здесь» ответившему устройству.
	for _, u := range calleePage.Updates {
		if u.CallID == talked && u.Change == "answered" && !u.Here {
			t.Fatal("ответившему устройству не сказано «взяли здесь»")
		}
	}
}

func TestСрокЗакрываетНеотвеченныйЗвонок(t *testing.T) {
	quietCallTimers(t, time.Hour, 200*time.Millisecond)
	ts, srv := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000080")
	callee := registerDevice(t, ts, "+79990000081")

	callID := startTestCall(t, ts, caller.token, callee.userID)
	time.Sleep(time.Second)

	call, err := srv.Store.GetCall(context.Background(), callID)
	if err != nil {
		t.Fatal(err)
	}
	if call.State != "missed" {
		t.Fatalf("звонок никто не закрыл, а он %q — «пропущенный» не родится", call.State)
	}
	if !hasChange(updatesOf(t, ts, callee.token, 0), callID, "missed") ||
		!hasChange(updatesOf(t, ts, caller.token, 0), callID, "missed") {
		t.Fatal("«пропущен» не дошёл до обеих сторон")
	}
}

func TestВычищеннаяЛентаДаётРазрыв(t *testing.T) {
	quietCallTimers(t, time.Hour, time.Hour)
	ts, srv := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000082")
	callee := registerDevice(t, ts, "+79990000083")

	startTestCall(t, ts, caller.token, callee.userID)
	if _, err := srv.Store.GCCallUpdates(context.Background(), -time.Hour); err != nil {
		t.Fatal(err)
	}
	page := updatesOf(t, ts, callee.token, 0)
	if !page.Gap {
		t.Fatalf("начало ленты вычищено, а разрыва нет: %+v", page)
	}
}

func TestПросмотренныйПропущенныйЕдетВсемУстройствам(t *testing.T) {
	quietCallTimers(t, time.Hour, time.Hour)
	ts, _ := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000084")
	callee := registerDevice(t, ts, "+79990000085")

	callID := startTestCall(t, ts, caller.token, callee.userID)
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls/"+callID+"/end", nil, nil); code != 200 {
		t.Fatalf("end: %d", code)
	}
	if code := postAuthed(t, ts, callee.token, "POST", "/api/v1/calls/seen",
		map[string]any{"call_ids": []string{callID}}, nil); code != 200 {
		t.Fatalf("seen: %d", code)
	}
	if !hasChange(updatesOf(t, ts, callee.token, 0), callID, "seen") {
		t.Fatal("«просмотрено» не легло в ленту")
	}
	// Про чужой звонок «просмотрено» не говорится.
	stranger := registerDevice(t, ts, "+79990000086")
	var out struct {
		Marked int `json:"marked"`
	}
	postAuthed(t, ts, stranger.token, "POST", "/api/v1/calls/seen",
		map[string]any{"call_ids": []string{callID}}, &out)
	if out.Marked != 0 {
		t.Fatal("посторонний отметил чужой звонок просмотренным")
	}
}
