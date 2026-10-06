package api

// Интеграционный тест WS-доставки: PostgreSQL + Redis из dev-compose.
// Получатель держит WS, отправитель шлёт REST-конверт → message.new приходит
// по WS и расшифровывается; ротация GK → key.rotated.

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"
	"google.golang.org/protobuf/proto"

	"tima/server/internal/events"
	pb "tima/server/internal/proto"
)

func setupWithEvents(t *testing.T) (*httptest.Server, *Server) {
	t.Helper()
	ts, srv := setup(t)
	redisURL := os.Getenv("TIMA_TEST_REDIS_URL")
	if redisURL == "" {
		redisURL = "redis://:tima-dev-only@localhost:6379"
	}
	bus, err := events.New(context.Background(), redisURL)
	if err != nil {
		t.Skipf("Redis недоступен (%v) — подними deploy/docker-compose.dev.yml", err)
	}
	t.Cleanup(func() { bus.Close() })
	srv.Events = bus
	return ts, srv
}

// dialWS подключает устройство: auth первым кадром → ok.
func dialWS(t *testing.T, ts *httptest.Server, token string) *websocket.Conn {
	t.Helper()
	conn, _ := dialWSHello(t, ts, token)
	return conn
}

// dialWSHello — то же, но отдаёт и само приветствие: в нём вершины полос (П3), и тест,
// которому они нужны, второго приветствия не дождётся — оно одно.
func dialWSHello(t *testing.T, ts *httptest.Server, token string) (*websocket.Conn, []byte) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	t.Cleanup(cancel)
	url := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	conn, _, err := websocket.Dial(ctx, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	raw, _ := json.Marshal(map[string]string{"token": token})
	if err := conn.Write(ctx, websocket.MessageText, raw); err != nil {
		t.Fatal(err)
	}
	var ok struct {
		Event string `json:"event"`
	}
	_, frame, err := conn.Read(ctx)
	if err != nil || json.Unmarshal(frame, &ok) != nil || ok.Event != "ok" {
		t.Fatalf("ожидался кадр ok, получено %q (err=%v)", frame, err)
	}
	return conn, frame
}

func readEvent(t *testing.T, conn *websocket.Conn, wantEvent string) map[string]json.RawMessage {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, frame, err := conn.Read(ctx)
	if err != nil {
		t.Fatalf("WS-кадр не пришёл: %v", err)
	}
	var m map[string]json.RawMessage
	if err := json.Unmarshal(frame, &m); err != nil {
		t.Fatalf("кадр не JSON: %q", frame)
	}
	var event string
	_ = json.Unmarshal(m["event"], &event)
	if event != wantEvent {
		t.Fatalf("ожидалось событие %s, пришло %s", wantEvent, event)
	}
	return m
}

// readByPoke — событие так, как его получает клиент: по шине едет подсказка `sync.poke`
// с номером, а не тело (2af7ee34, план П4), и тело забирается догоном с этого номера.
func readByPoke(t *testing.T, conn *websocket.Conn, wantEvent string) map[string]json.RawMessage {
	t.Helper()
	poke := readEvent(t, conn, "sync.poke")
	var id int64
	_ = json.Unmarshal(poke["event_id"], &id)
	if id <= 0 {
		t.Fatalf("в подсказке нет номера события: %v", poke)
	}
	events, _, _ := pull(t, conn, id-1)
	for _, e := range events {
		var got int64
		_ = json.Unmarshal(e["event_id"], &got)
		if got != id {
			continue
		}
		var event string
		_ = json.Unmarshal(e["event"], &event)
		if event != wantEvent {
			t.Fatalf("по подсказке %d пришло %s, ожидалось %s", id, event, wantEvent)
		}
		return e
	}
	t.Fatalf("догон с %d не вернул события из подсказки", id-1)
	return nil
}

func TestWSDeliversMessageNew(t *testing.T) {
	ts, _ := setupWithEvents(t)
	sender := registerDevice(t, ts, "+79993330001")
	recipient := registerDevice(t, ts, "+79993330002")

	conn := dialWS(t, ts, recipient.token)

	// Плохой токен → соединение закрывается policy violation
	badCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	badConn, _, err := websocket.Dial(badCtx, "ws"+strings.TrimPrefix(ts.URL, "http")+"/ws", nil)
	if err == nil {
		raw, _ := json.Marshal(map[string]string{"token": "мусор"})
		_ = badConn.Write(badCtx, websocket.MessageText, raw)
		if _, _, err := badConn.Read(badCtx); err == nil {
			t.Fatal("подделанный токен: соединение обязано закрыться")
		}
		badConn.CloseNow()
	}

	// Отправка REST-ом — доставка WS-ом
	plaintext := []byte("живая доставка TIMA ⚡")
	env := sealEnvelope(t, sender, []*device{recipient}, 3001, plaintext)
	resp := post(t, ts, env, sender.token, "eeeeeeee-0000-0000-0000-000000003001")
	resp.Body.Close()
	if resp.StatusCode != 201 {
		t.Fatalf("POST: %d", resp.StatusCode)
	}

	frame := readByPoke(t, conn, "message.new")
	var envB64 string
	_ = json.Unmarshal(frame["envelope"], &envB64)
	raw, err := base64.RawURLEncoding.DecodeString(envB64)
	if err != nil {
		t.Fatal(err)
	}
	var got pb.Envelope
	if err := proto.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if len(got.GetWrappedKeys()) != 1 || got.GetWrappedKeys()[0].GetRecipient() != recipient.id {
		t.Fatal("в WS-конверте должна быть ровно одна обёртка устройства-адресата")
	}

	// Расшифровка как настоящий клиент
	wrapped := got.GetWrappedKeys()[0].GetWrapped()
	var wnonce [24]byte
	copy(wnonce[:], wrapped[:24])
	var ephPub [32]byte
	copy(ephPub[:], got.GetSenderEphemeralPub())
	keyBytes, ok := box.Open(nil, wrapped[24:], &wnonce, &ephPub, &recipient.encPriv)
	if !ok {
		t.Fatal("wrapped_key из WS не развернулся")
	}
	var messageKey [32]byte
	copy(messageKey[:], keyBytes)
	var pnonce [24]byte
	copy(pnonce[:], got.GetEncryptedPayload()[:24])
	opened, ok := secretbox.Open(nil, got.GetEncryptedPayload()[24:], &pnonce, &messageKey)
	if !ok || !bytes.Equal(opened, plaintext) {
		t.Fatal("payload из WS-события не расшифровался")
	}
}

func TestWSDeliversKeyRotated(t *testing.T) {
	ts, _ := setupWithEvents(t)
	admin := registerDevice(t, ts, "+79993330003")
	member := registerDevice(t, ts, "+79993330004")

	groupID := createGroupAPI(t, ts, admin.token)
	addMemberAPI(t, ts, admin.token, groupID, member.userID, "member")

	conn := dialWS(t, ts, member.token)

	gk, code := doRotate(t, ts, admin.token, groupID, 1, "periodic", []*device{admin, member})
	if code != 201 {
		t.Fatalf("ротация: %d", code)
	}

	frame := readByPoke(t, conn, "key.rotated")
	var wrappedB64, ephB64 string
	_ = json.Unmarshal(frame["wrapped_gk"], &wrappedB64)
	_ = json.Unmarshal(frame["sender_ephemeral_pub"], &ephB64)
	wrapped, err1 := base64.RawURLEncoding.DecodeString(wrappedB64)
	eph, err2 := base64.RawURLEncoding.DecodeString(ephB64)
	if err1 != nil || err2 != nil || len(eph) != 32 {
		t.Fatal("битые base64url в key.rotated")
	}
	var nonce [24]byte
	copy(nonce[:], wrapped[:24])
	var ephPub [32]byte
	copy(ephPub[:], eph)
	raw, ok := box.Open(nil, wrapped[24:], &nonce, &ephPub, &member.encPriv)
	if !ok || !bytes.Equal(raw, gk[:]) {
		t.Fatal("GK из key.rotated не развернулся в исходный")
	}
}

// Пинг сервера — только когда клиент молчит (заказчик 2026-10-06, батарея): клиент пингует
// сам каждые 18 с, и встречный поток пингов сервера будил телефон ещё 120 раз в час.
func TestWSServerPingsOnlyWhenClientSilent(t *testing.T) {
	before := wsPingInterval
	wsPingInterval = 300 * time.Millisecond
	t.Cleanup(func() { wsPingInterval = before })

	ts, _ := setupWithEvents(t)
	dev := registerDevice(t, ts, "+79993330091")

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	var serverPings atomic.Int32
	url := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	conn, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		OnPingReceived: func(context.Context, []byte) bool {
			serverPings.Add(1)
			return true
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.CloseNow()
	raw, _ := json.Marshal(map[string]string{"token": dev.token})
	if err := conn.Write(ctx, websocket.MessageText, raw); err != nil {
		t.Fatal(err)
	}
	if _, _, err := conn.Read(ctx); err != nil {
		t.Fatalf("кадр ok не пришёл: %v", err)
	}
	// Дальше кадров данных не будет — читатель нужен только для служебных кадров (ping/pong).
	readCtx := conn.CloseRead(ctx)

	// Клиент пингует чаще интервала сервера — сервер молчит.
	for i := 0; i < 10; i++ {
		pctx, pcancel := context.WithTimeout(readCtx, 2*time.Second)
		if err := conn.Ping(pctx); err != nil {
			pcancel()
			t.Fatalf("ping клиента не прошёл: %v", err)
		}
		pcancel()
		time.Sleep(100 * time.Millisecond)
	}
	if n := serverPings.Load(); n != 0 {
		t.Fatalf("клиент пинговал сам, а сервер прислал ещё %d ping", n)
	}

	// Клиент замолчал — сервер проверяет соединение сам.
	time.Sleep(1200 * time.Millisecond)
	if n := serverPings.Load(); n == 0 {
		t.Fatal("клиент молчит, а сервер не пингует — мёртвое соединение не заметили бы")
	}
}
