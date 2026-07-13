package api

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"tima/server/internal/calls"
)

// setupWithCalls добавляет LiveKit-issuer к тестовому серверу.
func setupWithCalls(t *testing.T) (*httptest.Server, *Server) {
	ts, srv := setup(t)
	srv.Calls = calls.NewIssuer("devkey", "devsecret_at_least_32_chars_long_000")
	srv.LiveKitURL = "ws://localhost:7880"
	return ts, srv
}

// TestCallFlow — звонок 1:1: создание (токен + call.incoming), ответ (токен), завершение.
func TestCallFlow(t *testing.T) {
	ts, _ := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000030")
	callee := registerDevice(t, ts, "+79990000031")

	var start struct {
		CallID string `json:"call_id"`
		Room   string `json:"room"`
		URL    string `json:"url"`
		Token  string `json:"token"`
	}
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": callee.userID, "kind": "audio"}, &start); code != 201 {
		t.Fatalf("startCall: %d", code)
	}
	if start.Room == "" || start.Token == "" {
		t.Fatalf("нет room/token: %+v", start)
	}
	if start.URL != "ws://localhost:7880" {
		t.Fatalf("url LiveKit не передан: %q", start.URL)
	}

	// Callee отвечает → получает свой токен
	var ans struct {
		Room  string `json:"room"`
		Token string `json:"token"`
	}
	if code := postAuthed(t, ts, callee.token, "POST", "/api/v1/calls/"+start.CallID+"/answer", nil, &ans); code != 200 {
		t.Fatalf("answer: %d", code)
	}
	if ans.Room != start.Room || ans.Token == "" {
		t.Fatalf("ответный токен неверен: %+v", ans)
	}

	// Посторонний не может ответить (уже answered, и он не callee)
	stranger := registerDevice(t, ts, "+79990000032")
	if code := postAuthed(t, ts, stranger.token, "POST", "/api/v1/calls/"+start.CallID+"/answer", nil, nil); code != http.StatusForbidden {
		t.Fatalf("посторонний ответ: ожидался 403, получен %d", code)
	}

	// Завершение участником
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls/"+start.CallID+"/end", nil, nil); code != 200 {
		t.Fatalf("end: %d", code)
	}
}

// TestCallsUnconfigured — без LiveKit /calls отвечает 503.
func TestCallsUnconfigured(t *testing.T) {
	ts, _ := setup(t) // без Calls
	caller := registerDevice(t, ts, "+79990000033")
	callee := registerDevice(t, ts, "+79990000034")
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": callee.userID, "kind": "audio"}, nil); code != http.StatusServiceUnavailable {
		t.Fatalf("без LiveKit: ожидался 503, получен %d", code)
	}
}
