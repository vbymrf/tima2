package api

// Дожим по журналу: кадр, потерянный шиной, приходит устройству САМ, без обрыва
// соединения и без переподключения.
//
// Проверять это можно только целиком — сервер, Redis, сокет: беда 2026-09-20 в том и
// состояла, что каждая половина работала правильно. Событие лежало в device_events,
// шина его не донесла, и клиент про него не знал, потому что читает журнал только при
// подключении. Здесь потеря шины изображается прямой записью в журнал мимо Publish —
// ровно то, что и происходит, когда Pub/Sub теряет кадр.

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

func TestПотерянныйШинойКадрДосылаетсяЖурналом(t *testing.T) {
	ts, srv := setupWithEvents(t)
	dev := registerDevice(t, ts, "+79996660051")

	conn := dialWS(t, ts, dev.token)
	// Догон обязателен: до первого sync.pull сервер не знает, откуда устройство
	// читает, и дожимать не вправе.
	if _, _, more := pull(t, conn, 0); more {
		t.Fatal("у свежего устройства истории быть не должно")
	}

	// Событие есть в журнале, но по шине НЕ уходило.
	payload, _ := json.Marshal(map[string]any{
		"call_id": "11111111-0000-0000-0000-000000000051",
		"room":    "call-проба",
		"kind":    "audio",
		"from":    "22222222-0000-0000-0000-000000000051",
	})
	lost, err := srv.Store.AppendDeviceEvent(context.Background(), dev.id, "call.incoming", payload)
	if err != nil {
		t.Fatal(err)
	}

	// Ждём дольше одного оборота сверки: соединение живое, просить клиент ничего не
	// будет — кадр обязан прийти сам.
	deadline := time.Now().Add(wsCatchupInterval * 3)
	for {
		if time.Now().After(deadline) {
			t.Fatalf("событие %d так и не дошло: потеря шины остаётся потерей", lost)
		}
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		_, raw, err := conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("соединение не пережило ожидания: %v", err)
		}
		var frame struct {
			Event   string `json:"event"`
			EventID int64  `json:"event_id"`
		}
		if json.Unmarshal(raw, &frame) != nil {
			continue
		}
		if frame.EventID != lost {
			continue
		}
		if frame.Event != "call.incoming" {
			t.Fatalf("досланный кадр пришёл как %q, а не call.incoming", frame.Event)
		}
		break
	}
}

func TestДожимНеШлётОдноСобытиеДважды(t *testing.T) {
	ts, srv := setupWithEvents(t)
	dev := registerDevice(t, ts, "+79996660052")

	conn := dialWS(t, ts, dev.token)
	pull(t, conn, 0)

	payload, _ := json.Marshal(map[string]any{"call_id": "проба", "state": "ended"})
	id, err := srv.Store.AppendDeviceEvent(context.Background(), dev.id, "call.state", payload)
	if err != nil {
		t.Fatal(err)
	}

	// Два оборота сверки подряд: первый досылает, второй обязан промолчать. Иначе
	// устройство получало бы один и тот же вызов каждые пять секунд, пока живёт
	// соединение, — а клиентский отбор по event_id это бы спрятал.
	seen := 0
	deadline := time.Now().Add(wsCatchupInterval * 3)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		_, raw, err := conn.Read(ctx)
		cancel()
		if err != nil {
			break // истёк срок ожидания — больше ничего не пришло, и это ответ
		}
		var frame struct {
			EventID int64 `json:"event_id"`
		}
		if json.Unmarshal(raw, &frame) == nil && frame.EventID == id {
			seen++
		}
	}
	if seen != 1 {
		t.Fatalf("событие %d пришло %d раз(а), ожидался ровно один", id, seen)
	}
	// Живость соединения здесь не проверить: в coder/websocket истёкший срок чтения
	// рвёт сокет, а ждать мы обязаны именно до срока — тем и доказывается, что второй
	// раз кадр не пришёл. Что соединение переживает дожим, показывает тест выше.
}
