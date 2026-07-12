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
	return conn
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

	frame := readEvent(t, conn, "message.new")
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

	frame := readEvent(t, conn, "key.rotated")
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
