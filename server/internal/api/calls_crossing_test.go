package api

import (
	"fmt"
	"sync"
	"testing"
)

// Встречный вызов (заказчик 2026-11-20: «соединяем»; отчёты 47H7, DZKC, LF57).

type crossStart struct {
	CallID  string `json:"call_id"`
	Room    string `json:"room"`
	Token   string `json:"token"`
	Crossed bool   `json:"crossed"`
	Code    string `json:"code"`
}

// TestCrossingCallJoins — набрал того, кто уже звонит мне: не «занят», а вход в его звонок ответившим.
func TestCrossingCallJoins(t *testing.T) {
	ts, _ := setupWithCalls(t)
	a := registerDevice(t, ts, "+79990000070")
	b := registerDevice(t, ts, "+79990000071")

	var first crossStart
	if code := postAuthed(t, ts, a.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": b.userID, "kind": "video"}, &first); code != 201 {
		t.Fatalf("первый вызов: %d", code)
	}
	var second crossStart
	code := postAuthed(t, ts, b.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": a.userID, "kind": "video"}, &second)
	if code != 201 {
		t.Fatalf("встречный вызов отказан: %d %q — прежде здесь было «занят»", code, second.Code)
	}
	if !second.Crossed || second.CallID != first.CallID || second.Room != first.Room || second.Token == "" {
		t.Fatalf("встречный не вошёл в первый звонок: crossed=%v звонок=%s комната=%v (первый %s)", second.Crossed, second.CallID, second.Room == first.Room, first.CallID)
	}
	// Звонящему — «ответили», как при «Принять».
	if !hasChange(updatesOf(t, ts, a.token, 0), first.CallID, "answered") {
		t.Fatal("первому не сказали «ответили»")
	}
}

// TestCrossingAtOnce — двое нажимают в одну и ту же секунду: звонок один, второй вошёл в него.
// Без замка пары оба проходили проверку встречного, пока звонка ещё нет, и заводили по своему.
func TestCrossingAtOnce(t *testing.T) {
	ts, _ := setupWithCalls(t)
	for round := 0; round < 5; round++ {
		a := registerDevice(t, ts, fmt.Sprintf("+7999000008%d", round*2))
		b := registerDevice(t, ts, fmt.Sprintf("+7999000008%d", round*2+1))
		var got [2]crossStart
		var codes [2]int
		var wg sync.WaitGroup
		for i, pair := range [][2]*device{{a, b}, {b, a}} {
			wg.Add(1)
			go func(i int, from, to *device) {
				defer wg.Done()
				codes[i] = postAuthed(t, ts, from.token, "POST", "/api/v1/calls",
					map[string]string{"peer_id": to.userID, "kind": "audio"}, &got[i])
			}(i, pair[0], pair[1])
		}
		wg.Wait()
		if codes[0] != 201 || codes[1] != 201 {
			t.Fatalf("круг %d: коды %v, отказы %q %q", round, codes, got[0].Code, got[1].Code)
		}
		if got[0].CallID != got[1].CallID || got[0].Crossed == got[1].Crossed {
			t.Fatalf("круг %d: два звонка вместо одного: %s и %s", round, got[0].CallID, got[1].CallID)
		}
	}
}

// TestLateBusyKeepsAnswered — «занят» от устройства, опоздавшего к уже отвеченному звонку, его не кладёт.
func TestLateBusyKeepsAnswered(t *testing.T) {
	ts, _ := setupWithCalls(t)
	a := registerDevice(t, ts, "+79990000090")
	b := registerDevice(t, ts, "+79990000091")
	var start crossStart
	if code := postAuthed(t, ts, a.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": b.userID, "kind": "audio"}, &start); code != 201 {
		t.Fatalf("вызов: %d", code)
	}
	if code := postAuthed(t, ts, b.token, "POST", "/api/v1/calls/"+start.CallID+"/answer", nil, nil); code != 200 {
		t.Fatalf("answer: %d", code)
	}
	var end struct {
		State string `json:"state"`
	}
	if code := postAuthed(t, ts, b.token, "POST", "/api/v1/calls/"+start.CallID+"/end?reason=busy", nil, &end); code != 200 {
		t.Fatalf("end?reason=busy: %d", code)
	}
	if end.State != "answered" {
		t.Fatalf("опоздавшее «занят» положило разговор: %q", end.State)
	}
	// Настоящий конец разговора по-прежнему кладёт.
	if code := postAuthed(t, ts, b.token, "POST", "/api/v1/calls/"+start.CallID+"/end", nil, &end); code != 200 || end.State != "ended" {
		t.Fatalf("конец разговора: %d %q", code, end.State)
	}
}
