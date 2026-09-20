package api

// «Вызов никто не забрал» — слово звонящему, а не приговор собеседнику.
//
// Проверяется ровно граница: сказано, когда подтверждения нет, и молчим, когда оно
// есть. Всё остальное про этот кадр — в ADR-0025 §1а: устройство говорит только о
// своём состоянии и только когда доступно.

import (
	"context"
	"testing"
	"time"
)

func TestНезабранныйВызовСообщаетсяЗвонящему(t *testing.T) {
	ts, srv := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000060")
	callee := registerDevice(t, ts, "+79990000061")

	// Сроки укорачиваем: ждать пять секунд проверке незачем. Повторы вызова гасим —
	// они к этому кадру отношения не имеют и только удлиняют прогон.
	wasNotice, wasRing := unreachableAfter, ringAgainAfter
	unreachableAfter, ringAgainAfter = 200*time.Millisecond, nil
	defer func() { unreachableAfter, ringAgainAfter = wasNotice, wasRing }()

	var start struct {
		CallID string `json:"call_id"`
	}
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": callee.userID, "kind": "audio"}, &start); code != 201 {
		t.Fatalf("startCall: %d", code)
	}

	time.Sleep(time.Second)

	// Устройство собеседника ничего не подтверждало — значит его сейчас нет на связи,
	// и звонящий вправе это знать, а не слушать гудки сорок пять секунд.
	if n := countEvents(t, srv, caller.id, "call.unreachable", start.CallID); n != 1 {
		t.Fatalf("кадров call.unreachable %d, ожидался 1", n)
	}
	// И при этом звонок ЖИВ: устройство может вернуться и взять вызов из журнала.
	call, err := srv.Store.GetCall(context.Background(), start.CallID)
	if err != nil {
		t.Fatal(err)
	}
	if call.State != "ringing" {
		t.Fatalf("звонок стал %q — слово о связи не должно класть трубку", call.State)
	}
}

func TestЗабранныйВызовМолчит(t *testing.T) {
	ts, srv := setupWithCalls(t)
	caller := registerDevice(t, ts, "+79990000062")
	callee := registerDevice(t, ts, "+79990000063")

	wasNotice, wasRing := unreachableAfter, ringAgainAfter
	unreachableAfter, ringAgainAfter = 500*time.Millisecond, nil
	defer func() { unreachableAfter, ringAgainAfter = wasNotice, wasRing }()

	var start struct {
		CallID string `json:"call_id"`
	}
	if code := postAuthed(t, ts, caller.token, "POST", "/api/v1/calls",
		map[string]string{"peer_id": callee.userID, "kind": "audio"}, &start); code != 201 {
		t.Fatalf("startCall: %d", code)
	}

	// Устройство собеседника подтверждает вызов — ровно то, что делает живой клиент
	// сразу после разбора кадра (EventStream.ack).
	ctx := context.Background()
	took, err := srv.Store.MaxDeviceEventID(ctx, callee.id)
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Store.SetSyncCursor(ctx, callee.id, took); err != nil {
		t.Fatal(err)
	}

	time.Sleep(time.Second)

	if n := countEvents(t, srv, caller.id, "call.unreachable", start.CallID); n != 0 {
		t.Fatalf("кадров call.unreachable %d, ожидалось 0: вызов забрали", n)
	}
}
