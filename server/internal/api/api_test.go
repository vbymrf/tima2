package api

// Интеграционный тест Message Service с реальным PostgreSQL (dev-compose).
// Пропускается, если база недоступна. Криптография — настоящая, тем же конвейером,
// что клиентский messenger-crypto: тест «клиента» шифрует и подписывает конверт,
// сервер проверяет подпись и раскладывает, «получатель» разворачивает и читает.

import (
	"bytes"
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"
	"google.golang.org/protobuf/proto"

	timacrypto "tima/server/internal/crypto"
	pb "tima/server/internal/proto"
	"tima/server/internal/store"
	"tima/server/migrations"
)

const (
	chatID   = "aaaaaaaa-0000-0000-0000-00000000c4a7"
	senderID = "bbbbbbbb-0000-0000-0000-0000000c0de1"
	recipID  = "cccccccc-0000-0000-0000-0000000c0de2"
)

type device struct {
	id      string
	encPriv [32]byte
	encPub  [32]byte
	signKey ed25519.PrivateKey
}

func newDevice(t *testing.T, id string) *device {
	t.Helper()
	d := &device{id: id}
	if _, err := rand.Read(d.encPriv[:]); err != nil {
		t.Fatal(err)
	}
	pub, err := curve25519.X25519(d.encPriv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	copy(d.encPub[:], pub)
	seed := make([]byte, 32)
	if _, err := rand.Read(seed); err != nil {
		t.Fatal(err)
	}
	d.signKey = ed25519.NewKeyFromSeed(seed)
	return d
}

func setup(t *testing.T) (*httptest.Server, *store.Store) {
	t.Helper()
	url := os.Getenv("TIMA_TEST_DATABASE_URL")
	if url == "" {
		url = "postgres://tima:tima-dev-only@localhost:5432/tima"
	}
	st, err := store.New(context.Background(), url)
	if err != nil {
		t.Skipf("PostgreSQL недоступен (%v) — подними deploy/docker-compose.dev.yml", err)
	}
	t.Cleanup(st.Close)
	if err := st.Migrate(context.Background(), migrations.FS); err != nil {
		t.Fatal(err)
	}
	if err := st.ResetForTests(context.Background()); err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	(&Server{Store: st}).Register(mux)
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	return ts, st
}

func registerDevice(t *testing.T, ts *httptest.Server, d *device, userID string) {
	t.Helper()
	b64 := base64.RawURLEncoding
	body, _ := json.Marshal(map[string]string{
		"device_id":      d.id,
		"user_id":        userID,
		"encryption_pub": b64.EncodeToString(d.encPub[:]),
		"signing_pub":    b64.EncodeToString(d.signKey.Public().(ed25519.PublicKey)),
	})
	resp, err := http.Post(ts.URL+"/api/v1/dev/devices", "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("регистрация устройства %s: %d", d.id, resp.StatusCode)
	}
}

// sealEnvelope повторяет клиентский конвейер (crypto-protocol.md §3.2) на Go.
func sealEnvelope(t *testing.T, sender *device, recipients []*device, messageID uint64, plaintext []byte) (*pb.Envelope, []byte) {
	t.Helper()
	var messageKey [32]byte
	if _, err := rand.Read(messageKey[:]); err != nil {
		t.Fatal(err)
	}

	// Слой 1: конверт
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		t.Fatal(err)
	}
	payload := secretbox.Seal(nonce[:], plaintext, &nonce, &messageKey)

	// Слой 2: escrow — серверу важны только размеры (он не может проверить семантику)
	escrowCt := bytes.Repeat([]byte{0xEC}, 1088)
	escrowWrapped := bytes.Repeat([]byte{0xED}, 24+16+32)

	// Слой 4: обёртки эфемерной парой на каждое устройство
	ephPub, ephPriv, err := box.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	var wrappedKeys []*pb.WrappedKey
	for _, r := range recipients {
		var wnonce [24]byte
		if _, err := rand.Read(wnonce[:]); err != nil {
			t.Fatal(err)
		}
		wrapped := box.Seal(wnonce[:], messageKey[:], &wnonce, &r.encPub, ephPriv)
		wrappedKeys = append(wrappedKeys, &pb.WrappedKey{Recipient: r.id, Wrapped: wrapped})
	}

	meta := &pb.Metadata{
		MessageId:       messageID,
		ChatId:          chatID,
		SenderId:        senderID,
		SenderDevice:    sender.id,
		Kind:            pb.ContentKind_CK_TEXT,
		CreatedAtUnixMs: 1_750_000_000_000,
	}
	env := &pb.Envelope{
		FormatVersion:      timacrypto.FormatVersion,
		Meta:               meta,
		EncryptedPayload:   payload,
		Escrow:             &pb.EscrowBlob{MlkemCt: escrowCt, WrappedMessageKey: escrowWrapped, EscrowKeyVersion: 1},
		SenderEphemeralPub: ephPub[:],
		WrappedKeys:        wrappedKeys,
	}
	cb := timacrypto.CanonicalBytes(env.FormatVersion, timacrypto.EnvelopeMeta{
		MessageID: messageID, ChatID: chatID, SenderID: senderID, SenderDevice: sender.id,
		Kind: uint32(meta.Kind), CreatedAtUnixMs: meta.CreatedAtUnixMs,
	}, payload, append(append([]byte{}, escrowCt...), escrowWrapped...), ephPub[:], nil)
	env.Signature = ed25519.Sign(sender.signKey, cb)
	return env, messageKey[:]
}

func post(t *testing.T, ts *httptest.Server, env *pb.Envelope, clientMsgID string) *http.Response {
	t.Helper()
	raw, err := proto.Marshal(env)
	if err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/messages", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/x-protobuf")
	req.Header.Set("X-Client-Msg-Id", clientMsgID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestMessageServiceEndToEnd(t *testing.T) {
	ts, _ := setup(t)
	sender := newDevice(t, "dddddddd-0000-0000-0000-000000000001")
	recipient := newDevice(t, "dddddddd-0000-0000-0000-000000000002")
	registerDevice(t, ts, sender, senderID)
	registerDevice(t, ts, recipient, recipID)

	plaintext := []byte("Сквозной тест TIMA: клиент → сервер → получатель 🚀")
	env, _ := sealEnvelope(t, sender, []*device{recipient, sender}, 1001, plaintext)

	// Приём
	const clientMsgID = "eeeeeeee-0000-0000-0000-000000001001"
	resp := post(t, ts, env, clientMsgID)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("POST /messages: %d", resp.StatusCode)
	}
	resp.Body.Close()

	// Повтор (тот же client_msg_id) → дедупликация, не вторая запись
	resp = post(t, ts, env, clientMsgID)
	var dup struct {
		Duplicate bool `json:"duplicate"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&dup)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || !dup.Duplicate {
		t.Fatalf("повторный POST: ожидался duplicate=true, статус %d", resp.StatusCode)
	}

	// История для устройства получателя
	req, _ := http.NewRequest("GET", fmt.Sprintf("%s/api/v1/chats/%s/messages", ts.URL, chatID), nil)
	req.Header.Set("X-Device-Id", recipient.id)
	histResp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer histResp.Body.Close()
	var hist struct {
		Messages []struct {
			MessageID uint64 `json:"message_id"`
			Envelope  string `json:"envelope"`
		} `json:"messages"`
	}
	if err := json.NewDecoder(histResp.Body).Decode(&hist); err != nil {
		t.Fatal(err)
	}
	if len(hist.Messages) == 0 {
		t.Fatal("история пуста")
	}

	// «Получатель»: парсим конверт, разворачиваем message_key, открываем payload
	raw, err := base64.RawURLEncoding.DecodeString(hist.Messages[0].Envelope)
	if err != nil {
		t.Fatal(err)
	}
	var got pb.Envelope
	if err := proto.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if len(got.GetWrappedKeys()) != 1 || got.GetWrappedKeys()[0].GetRecipient() != recipient.id {
		t.Fatal("в истории должна быть ровно одна обёртка — устройства-запросчика")
	}
	wrapped := got.GetWrappedKeys()[0].GetWrapped()
	var wnonce [24]byte
	copy(wnonce[:], wrapped[:24])
	var ephPub [32]byte
	copy(ephPub[:], got.GetSenderEphemeralPub())
	keyBytes, ok := box.Open(nil, wrapped[24:], &wnonce, &ephPub, &recipient.encPriv)
	if !ok {
		t.Fatal("не развернулся wrapped_key")
	}
	var messageKey [32]byte
	copy(messageKey[:], keyBytes)
	var pnonce [24]byte
	copy(pnonce[:], got.GetEncryptedPayload()[:24])
	opened, ok := secretbox.Open(nil, got.GetEncryptedPayload()[24:], &pnonce, &messageKey)
	if !ok {
		t.Fatal("payload не открылся")
	}
	if !bytes.Equal(opened, plaintext) {
		t.Fatalf("plaintext не совпал: %q", opened)
	}
}

func TestRejectsBadSignatureAndUnknownDevice(t *testing.T) {
	ts, _ := setup(t)
	sender := newDevice(t, "dddddddd-0000-0000-0000-000000000003")
	recipient := newDevice(t, "dddddddd-0000-0000-0000-000000000004")
	registerDevice(t, ts, sender, senderID)
	registerDevice(t, ts, recipient, recipID)

	// Повреждённая подпись → 403, в базу не попадает
	env, _ := sealEnvelope(t, sender, []*device{recipient}, 2001, []byte("тайна"))
	env.Signature[0] ^= 1
	resp := post(t, ts, env, "eeeeeeee-0000-0000-0000-000000002001")
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("повреждённая подпись: ожидался 403, получен %d", resp.StatusCode)
	}

	// Подмена метаданных после подписи → 403
	env2, _ := sealEnvelope(t, sender, []*device{recipient}, 2002, []byte("тайна"))
	env2.Meta.SenderId = recipID
	resp = post(t, ts, env2, "eeeeeeee-0000-0000-0000-000000002002")
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("подмена sender_id: ожидался 403, получен %d", resp.StatusCode)
	}

	// Незарегистрированное устройство → 403
	ghost := newDevice(t, "dddddddd-0000-0000-0000-00000000dead")
	env3, _ := sealEnvelope(t, ghost, []*device{recipient}, 2003, []byte("тайна"))
	env3.Meta.SenderDevice = ghost.id
	resp = post(t, ts, env3, "eeeeeeee-0000-0000-0000-000000002003")
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("неизвестное устройство: ожидался 403, получен %d", resp.StatusCode)
	}
}
