package api

import (
	"net/http/httptest"
	"testing"
	"time"
)

// Беда 2026-11-15 (обрыв личного звонка при смене сети у собеседника): уход из комнаты не сам — не конец.
// Ожидание в проверке короткое, суть та же, что у 20 секунд на стенде.

type directCall struct {
	CallID string `json:"call_id"`
	Room   string `json:"room"`
}

func answeredCall(t *testing.T, ts *httptest.Server, caller, callee *device) directCall {
	t.Helper()
	var start directCall
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": callee.userID, "kind": "audio"}, &start); code != 201 {
		t.Fatalf("звонок: %d", code)
	}
	if code := postAuthed(t, ts, callee.token, "POST", "/api/v1/calls/"+start.CallID+"/answer", nil, nil); code != 200 {
		t.Fatalf("ответ: %d", code)
	}
	return start
}

func roomEvent(t *testing.T, ts *httptest.Server, event, room string, who *device, reason any, at time.Time) {
	t.Helper()
	participant := map[string]any{"identity": who.userID + ":" + who.id}
	if reason != nil {
		participant["disconnectReason"] = reason
	}
	body, auth := livekitWebhookBody(t, map[string]any{
		"event": event, "room": map[string]any{"name": room}, "participant": participant, "createdAt": at.Unix(),
	})
	if code := postWebhook(t, ts, body, auth); code != 200 {
		t.Fatalf("вебхук %s: %d", event, code)
	}
}

func callState(t *testing.T, srv *Server, callID string) (string, string, time.Time) {
	t.Helper()
	c, err := srv.Store.GetCall(t.Context(), callID)
	if err != nil {
		t.Fatal(err)
	}
	return c.State, c.EndedBy, c.EndedAt
}

func TestDirectCallSurvivesReconnect(t *testing.T) {
	was := awayWait
	awayWait = 300 * time.Millisecond
	t.Cleanup(func() { awayWait = was })
	ts, srv := setup(t)
	withCalls(srv)
	a := registerDevice(t, ts, "+79990077001")
	b := registerDevice(t, ts, "+79990077002")

	// Замена подключения тем же устройством — звонок идёт.
	call := answeredCall(t, ts, a, b)
	roomEvent(t, ts, "participant_left", call.Room, b, "DUPLICATE_IDENTITY", time.Now())
	time.Sleep(awayWait * 2)
	if st, _, _ := callState(t, srv, call.CallID); st != "answered" {
		t.Fatalf("замена подключения закрыла звонок: %s", st)
	}

	// Обрыв без причины — ждём; вернулся за ожидание — звонок идёт.
	roomEvent(t, ts, "participant_left", call.Room, b, nil, time.Now())
	if st, _, _ := callState(t, srv, call.CallID); st != "answered" {
		t.Fatalf("обрыв закрыл звонок сразу: %s", st)
	}
	roomEvent(t, ts, "participant_joined", call.Room, b, nil, time.Now())
	time.Sleep(awayWait * 2)
	if st, _, _ := callState(t, srv, call.CallID); st != "answered" {
		t.Fatalf("вернувшийся потерял звонок: %s", st)
	}

	// Не вернулся — звонок закрыт, конец — момент ухода, ушедший записан завершившим.
	left := time.Now().Add(-5 * time.Second)
	roomEvent(t, ts, "participant_left", call.Room, b, "SIGNAL_CLOSE", left)
	time.Sleep(awayWait * 3)
	st, by, endedAt := callState(t, srv, call.CallID)
	if st != "ended" || by != b.userID {
		t.Fatalf("не вернувшийся: состояние %s, завершил %s", st, by)
	}
	if d := endedAt.Sub(left.Truncate(time.Second)); d < -time.Second || d > time.Second {
		t.Fatalf("конец записан не моментом ухода: %v против %v", endedAt, left)
	}
}

func TestDirectCallClientLeaveEndsAtOnce(t *testing.T) {
	ts, srv := setup(t)
	withCalls(srv)
	a := registerDevice(t, ts, "+79990077011")
	b := registerDevice(t, ts, "+79990077012")
	call := answeredCall(t, ts, a, b)
	roomEvent(t, ts, "participant_left", call.Room, b, "CLIENT_INITIATED", time.Now())
	if st, _, _ := callState(t, srv, call.CallID); st != "ended" {
		t.Fatalf("вышел сам — звонок не закрыт сразу: %s", st)
	}
}

func TestRedialWhileAwayClosesOldCall(t *testing.T) {
	was := awayWait
	awayWait = time.Hour
	t.Cleanup(func() { awayWait = was })
	ts, srv := setup(t)
	withCalls(srv)
	a := registerDevice(t, ts, "+79990077021")
	b := registerDevice(t, ts, "+79990077022")
	call := answeredCall(t, ts, a, b)
	// Б пропал без `/end` (нет сети) и перезванивает сам — «у вас уже идёт звонок» быть не должно.
	roomEvent(t, ts, "participant_left", call.Room, b, nil, time.Now())
	var again directCall
	if code := postAuthed(t, ts, b.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": a.userID, "kind": "audio"}, &again); code != 201 {
		t.Fatalf("повторный вызов во время ожидания: %d", code)
	}
	if st, by, _ := callState(t, srv, call.CallID); st != "ended" || by != b.userID {
		t.Fatalf("прежний звонок: %s, завершил %s", st, by)
	}
}
