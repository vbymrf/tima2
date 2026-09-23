package api

// Дожим по журналу: кадр, потерянный шиной, приходит устройству САМ, без обрыва
// соединения и без переподключения.
//
// Проверять это можно только целиком — сервер, Redis, сокет: беда 2026-09-20 в том и
// состояла, что каждая половина работала правильно. Событие лежало в device_events,
// шина его не донесла, и клиент про него не знал, потому что читает журнал только при
// подключении. Здесь потеря шины изображается прямой записью в журнал мимо Publish —
// ровно то, что и происходит, когда Pub/Sub теряет кадр.
//
// **Кадр звонка с переходом на подсказки (П2) приходит как `call.poke`**: тело
// протухает, идентификатор — нет.

import (
	"context"
	"encoding/json"
	"testing"
	"time"
)

// быстрыйДожим укорачивает оборот сверки на время проверки.
//
// Тридцать секунд выбраны для телефона в сети, а не для теста: ждать по полторы
// минуты на каждую проверку значило бы не проверять их вовсе.
func быстрыйДожим(t *testing.T) {
	t.Helper()
	было := wsCatchupInterval
	wsCatchupInterval = 300 * time.Millisecond
	t.Cleanup(func() { wsCatchupInterval = было })
}

func TestПотерянныйШинойКадрДосылаетсяЖурналом(t *testing.T) {
	быстрыйДожим(t)
	ts, srv := setupWithEvents(t)
	dev := registerDevice(t, ts, "+79996660051")

	conn := dialWS(t, ts, dev.token)
	// Догон обязателен: до первого sync.pull сервер не знает, откуда устройство
	// читает, и дожимать не вправе.
	if _, _, more := pull(t, conn, 0); more {
		t.Fatal("у свежего устройства истории быть не должно")
	}

	const callID = "11111111-0000-0000-0000-000000000051"
	// Событие есть в журнале, но по шине НЕ уходило.
	payload, _ := json.Marshal(map[string]any{
		"call_id": callID,
		"room":    "call-проба",
		"kind":    "audio",
		"from":    "22222222-0000-0000-0000-000000000051",
	})
	if _, _, err := srv.Store.AppendDeviceEvent(context.Background(), dev.id, "call.incoming", payload); err != nil {
		t.Fatal(err)
	}

	// Ждём дольше одного оборота сверки: соединение живое, просить клиент ничего не
	// будет — подсказка обязана прийти сама.
	deadline := time.Now().Add(wsCatchupInterval * 6)
	for {
		if time.Now().After(deadline) {
			t.Fatalf("подсказка про звонок %s так и не дошла: потеря шины остаётся потерей", callID)
		}
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		_, raw, err := conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("соединение не пережило ожидания: %v", err)
		}
		var frame struct {
			Event  string `json:"event"`
			CallID string `json:"call_id"`
			Room   string `json:"room"`
		}
		if json.Unmarshal(raw, &frame) != nil || frame.Event != "call.poke" {
			continue
		}
		if frame.CallID != callID {
			t.Fatalf("подсказка про чужой звонок: %q", frame.CallID)
		}
		// Тела в подсказке нет и быть не должно: состояние берётся ручкой, а комната
		// и токен — адресные, и класть их в рассылаемый кадр нельзя.
		if frame.Room != "" {
			t.Fatalf("подсказка принесла тело звонка: %q", frame.Room)
		}
		break
	}
}

func TestДожимНеШлётОдноСобытиеДважды(t *testing.T) {
	быстрыйДожим(t)
	ts, srv := setupWithEvents(t)
	dev := registerDevice(t, ts, "+79996660052")

	conn := dialWS(t, ts, dev.token)
	pull(t, conn, 0)

	const callID = "11111111-0000-0000-0000-000000000052"
	payload, _ := json.Marshal(map[string]any{"call_id": callID, "state": "ended"})
	if _, _, err := srv.Store.AppendDeviceEvent(context.Background(), dev.id, "call.state", payload); err != nil {
		t.Fatal(err)
	}

	// Два оборота сверки подряд: первый досылает, второй обязан промолчать. Иначе
	// устройство получало бы один и тот же вызов каждые полминуты, пока живёт
	// соединение, — а клиентский отбор это бы спрятал.
	seen := 0
	deadline := time.Now().Add(wsCatchupInterval * 6)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithDeadline(context.Background(), deadline)
		_, raw, err := conn.Read(ctx)
		cancel()
		if err != nil {
			break // истёк срок ожидания — больше ничего не пришло, и это ответ
		}
		var frame struct {
			Event  string `json:"event"`
			CallID string `json:"call_id"`
		}
		if json.Unmarshal(raw, &frame) == nil && frame.Event == "call.poke" && frame.CallID == callID {
			seen++
		}
	}
	if seen != 1 {
		t.Fatalf("подсказка про %s пришла %d раз(а), ожидалась ровно одна", callID, seen)
	}
	// Живость соединения здесь не проверить: в coder/websocket истёкший срок чтения
	// рвёт сокет, а ждать мы обязаны именно до срока — тем и доказывается, что второй
	// раз кадр не пришёл. Что соединение переживает дожим, показывает тест выше.
}

// TestПротухшийВызовНеДоезжаетНоОтметкуДвигает — П2, обе ловушки разом.
//
// Телефон, пролежавший офлайн сутки, не должен звонить по всем накопленным вызовам.
// Но курсор обязан через них перешагнуть: не перешагнёт — клиент просит с того же
// места вечно, сервер вечно пропускает, и очередь встаёт на пустом месте.
func TestПротухшийВызовНеДоезжаетНоОтметкуДвигает(t *testing.T) {
	// Срок укорачивается до нуля вместо старения записи: так проверяется то самое
	// правило, а не умение теста подделать время в базе.
	было := wsCallFrameTTL
	wsCallFrameTTL = -time.Second
	t.Cleanup(func() { wsCallFrameTTL = было })

	ts, srv := setupWithEvents(t)
	dev := registerDevice(t, ts, "+79996660053")

	payload, _ := json.Marshal(map[string]any{"call_id": "11111111-0000-0000-0000-000000000053"})
	старый, _, err := srv.Store.AppendDeviceEvent(context.Background(), dev.id, "call.incoming", payload)
	if err != nil {
		t.Fatal(err)
	}

	conn := dialWS(t, ts, dev.token)
	frames, next, _ := pull(t, conn, 0)

	for _, f := range frames {
		if видКадра(f) == "call.poke" {
			t.Fatalf("протухший вызов доехал до телефона: %v", f)
		}
	}
	if next < старый {
		t.Fatalf("отметка застряла на протухшем вызове: next=%d, событие=%d", next, старый)
	}
}

// видКадра — имя события из сырого кадра. Кадры приходят разобранными по полям, и
// сравнивать поле с литералом напрямую нельзя: это JSON, а не строка.
func видКадра(f map[string]json.RawMessage) string {
	var event string
	_ = json.Unmarshal(f["event"], &event)
	return event
}

// TestПолосаИНомерЕдутСКадром — П3: разрыв должен быть виден клиенту.
//
// `event_id` глобально монотонный: у устройства он идёт с дырами по построению, и
// отличить «моё потерялось» от «это было не моё» по нему нельзя. Номер в полосе
// плотный — по нему и видно.
func TestПолосаИНомерЕдутСКадром(t *testing.T) {
	ts, srv := setupWithEvents(t)
	dev := registerDevice(t, ts, "+79996660054")

	for i := 0; i < 2; i++ {
		payload, _ := json.Marshal(map[string]any{"chat_id": "проба"})
		if _, _, err := srv.Store.AppendDeviceEvent(context.Background(), dev.id, "message.new", payload); err != nil {
			t.Fatal(err)
		}
	}
	// Ключи — своя полоса: пропуск здесь означает не «сообщение опоздало», а
	// «сообщение не откроется никогда».
	keyPayload, _ := json.Marshal(map[string]any{"group_id": "проба"})
	if _, _, err := srv.Store.AppendDeviceEvent(context.Background(), dev.id, "key.rotated", keyPayload); err != nil {
		t.Fatal(err)
	}

	conn := dialWS(t, ts, dev.token)
	frames, _, _ := pull(t, conn, 0)

	var переписка, ключи []int64
	for _, f := range frames {
		raw, ok := f["lane"]
		if !ok {
			continue
		}
		var lane int16
		var seq int64
		_ = json.Unmarshal(raw, &lane)
		_ = json.Unmarshal(f["lane_seq"], &seq)
		switch lane {
		case 1:
			переписка = append(переписка, seq)
		case 2:
			ключи = append(ключи, seq)
		}
	}
	if len(переписка) != 2 || переписка[0] != 1 || переписка[1] != 2 {
		t.Fatalf("номера переписки не плотные: %v", переписка)
	}
	if len(ключи) != 1 || ключи[0] != 1 {
		t.Fatalf("полоса ключей считается вместе с перепиской: %v", ключи)
	}
}

// TestВершиныПолосПриходятВПриветствии — иначе первый разрыв не с чем сверить.
//
// Устройство, молчавшее неделю, сверяло бы свой давний номер с новым и объявляло
// разрыв там, где его нет.
func TestВершиныПолосПриходятВПриветствии(t *testing.T) {
	ts, srv := setupWithEvents(t)
	dev := registerDevice(t, ts, "+79996660055")

	payload, _ := json.Marshal(map[string]any{"chat_id": "проба"})
	if _, _, err := srv.Store.AppendDeviceEvent(context.Background(), dev.id, "message.new", payload); err != nil {
		t.Fatal(err)
	}

	conn := dialWS(t, ts, dev.token)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, raw, err := conn.Read(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var hello struct {
		Event string `json:"event"`
		Pts   int64  `json:"pts"`
		Qts   int64  `json:"qts"`
	}
	if json.Unmarshal(raw, &hello) != nil || hello.Event != "ok" {
		t.Fatalf("первым кадром пришло не приветствие: %s", raw)
	}
	if hello.Pts != 1 {
		t.Fatalf("вершина полосы переписки в приветствии: %d, ожидалась 1", hello.Pts)
	}
	if hello.Qts != 0 {
		t.Fatalf("полоса ключей ненулевая, хотя ключей не слали: %d", hello.Qts)
	}
}
