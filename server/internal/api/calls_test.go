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

// TestVoiceRoomFlow — аудио-чат: создание, список, присоединение (LiveKit-токен).
func TestVoiceRoomFlow(t *testing.T) {
	ts, _ := setupWithCalls(t)
	owner := registerDevice(t, ts, "+79990000040")
	guest := registerDevice(t, ts, "+79990000041")

	var created struct {
		RoomID string `json:"room_id"`
	}
	if code := postAuthed(t, ts, owner.token, "POST", "/api/v1/voice-rooms",
		map[string]string{"title": "Standup"}, &created); code != 201 {
		t.Fatalf("createVoiceRoom: %d", code)
	}
	// Гость видит комнату в списке
	var list struct {
		Rooms []struct {
			RoomID string `json:"room_id"`
			Title  string `json:"title"`
		} `json:"rooms"`
	}
	if code := getAuthed(t, ts, guest.token, "/api/v1/voice-rooms", &list); code != 200 {
		t.Fatalf("listVoiceRooms: %d", code)
	}
	found := false
	for _, r := range list.Rooms {
		if r.RoomID == created.RoomID && r.Title == "Standup" {
			found = true
		}
	}
	if !found {
		t.Fatal("созданный аудио-чат обязан быть в списке")
	}
	// Гость присоединяется → получает токен LiveKit
	var join struct {
		Room  string `json:"room"`
		Token string `json:"token"`
	}
	if code := postAuthed(t, ts, guest.token, "POST", "/api/v1/voice-rooms/"+created.RoomID+"/join", nil, &join); code != 200 {
		t.Fatalf("join: %d", code)
	}
	if join.Room == "" || join.Token == "" {
		t.Fatalf("нет room/token: %+v", join)
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
