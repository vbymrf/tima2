package api

// Интеграционный тест синхронизации (sync-offline.md §2): событие, случившееся
// пока устройство было офлайн, приходит при подключении через sync.pull по
// cursor; ack двигает серверный cursor; live и догон несут один event_id.

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"testing"
	"time"

	"github.com/coder/websocket"
	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"
	"google.golang.org/protobuf/proto"

	pb "tima/server/internal/proto"
)

// wsSend шлёт клиентский кадр.
func wsSend(t *testing.T, conn *websocket.Conn, frame map[string]any) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	raw, _ := json.Marshal(frame)
	if err := conn.Write(ctx, websocket.MessageText, raw); err != nil {
		t.Fatal(err)
	}
}

// pull выполняет sync.pull и читает события до sync.done.
func pull(t *testing.T, conn *websocket.Conn, cursor any) (events []map[string]json.RawMessage, nextCursor int64, more bool) {
	t.Helper()
	frame := map[string]any{"event": "sync.pull"}
	if cursor != nil {
		frame["cursor"] = cursor
	}
	wsSend(t, conn, frame)
	for {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		_, raw, err := conn.Read(ctx)
		cancel()
		if err != nil {
			t.Fatalf("sync-кадр не пришёл: %v", err)
		}
		var m map[string]json.RawMessage
		if err := json.Unmarshal(raw, &m); err != nil {
			t.Fatalf("кадр не JSON: %q", raw)
		}
		var event string
		_ = json.Unmarshal(m["event"], &event)
		if event == "sync.done" {
			_ = json.Unmarshal(m["next_cursor"], &nextCursor)
			_ = json.Unmarshal(m["more"], &more)
			return events, nextCursor, more
		}
		events = append(events, m)
	}
}

func TestSyncPullAfterOffline(t *testing.T) {
	ts, _ := setupWithEvents(t)
	sender := registerDevice(t, ts, "+79996660001")
	recipient := registerDevice(t, ts, "+79996660002")

	// Получатель ОФЛАЙН; отправитель шлёт два конверта
	plaintext1 := []byte("первое сообщение мимо офлайна")
	env1 := sealEnvelope(t, sender, []*device{recipient}, 3001, plaintext1)
	resp := post(t, ts, env1, sender.token, "eeeeeeee-0000-0000-0000-000000003001")
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("POST 1: %d", resp.StatusCode)
	}
	env2 := sealEnvelope(t, sender, []*device{recipient}, 3002, []byte("второе"))
	resp = post(t, ts, env2, sender.token, "eeeeeeee-0000-0000-0000-000000003002")
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("POST 2: %d", resp.StatusCode)
	}

	// Подключение: sync.pull с cursor=0 отдаёт оба message.new по порядку
	conn := dialWS(t, ts, recipient.token)
	events, next, more := pull(t, conn, 0)
	if len(events) != 2 || more {
		t.Fatalf("догон: %d событий (more=%v), ожидалось 2", len(events), more)
	}
	var ev1, ev2 int64
	_ = json.Unmarshal(events[0]["event_id"], &ev1)
	_ = json.Unmarshal(events[1]["event_id"], &ev2)
	if ev1 <= 0 || ev2 <= ev1 || next != ev2 {
		t.Fatalf("event_id не монотонны: %d, %d (next=%d)", ev1, ev2, next)
	}

	// Конверт из догона расшифровывается как настоящий клиент
	var envB64 string
	_ = json.Unmarshal(events[0]["envelope"], &envB64)
	raw, err := base64.RawURLEncoding.DecodeString(envB64)
	if err != nil {
		t.Fatal(err)
	}
	var got pb.Envelope
	if err := proto.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if len(got.GetWrappedKeys()) != 1 || got.GetWrappedKeys()[0].GetRecipient() != recipient.id {
		t.Fatal("в событии догона должна быть ровно одна обёртка устройства")
	}
	opened := openEnvelope(t, &got, recipient)
	if !bytes.Equal(opened, plaintext1) {
		t.Fatalf("plaintext из догона не совпал: %q", opened)
	}

	// ack → серверный cursor; повторный pull без cursor (серверная копия) пуст
	wsSend(t, conn, map[string]any{"event": "ack", "event_id": next})
	deadline := time.Now().Add(5 * time.Second)
	for {
		events, _, _ = pull(t, conn, nil)
		if len(events) == 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("после ack pull без cursor вернул %d событий, ожидалось 0", len(events))
		}
		time.Sleep(50 * time.Millisecond) // ack асинхронен относительно pull
	}

	// Явный cursor клиента имеет приоритет: с cursor=0 события выдаются снова (идемпотентность)
	events, _, _ = pull(t, conn, 0)
	if len(events) != 2 {
		t.Fatalf("повторный догон с cursor=0: %d событий, ожидалось 2", len(events))
	}

	// Live после догона: новый конверт приходит с бОльшим event_id
	env3 := sealEnvelope(t, sender, []*device{recipient}, 3003, []byte("третье, уже live"))
	resp = post(t, ts, env3, sender.token, "eeeeeeee-0000-0000-0000-000000003003")
	resp.Body.Close()
	live := readEvent(t, conn, "message.new")
	var liveID int64
	_ = json.Unmarshal(live["event_id"], &liveID)
	if liveID <= ev2 {
		t.Fatalf("live event_id %d не больше последнего из лога %d", liveID, ev2)
	}
}

// openEnvelope — сторона получателя: wrapped_key → message_key → plaintext.
func openEnvelope(t *testing.T, env *pb.Envelope, recipient *device) []byte {
	t.Helper()
	wrapped := env.GetWrappedKeys()[0].GetWrapped()
	var wnonce [24]byte
	copy(wnonce[:], wrapped[:24])
	var ephPub [32]byte
	copy(ephPub[:], env.GetSenderEphemeralPub())
	keyBytes, ok := box.Open(nil, wrapped[24:], &wnonce, &ephPub, &recipient.encPriv)
	if !ok {
		t.Fatal("wrapped_key не развернулся")
	}
	var messageKey [32]byte
	copy(messageKey[:], keyBytes)
	payload := env.GetEncryptedPayload()
	var pnonce [24]byte
	copy(pnonce[:], payload[:24])
	opened, ok := secretbox.Open(nil, payload[24:], &pnonce, &messageKey)
	if !ok {
		t.Fatal("payload не открылся")
	}
	return opened
}
