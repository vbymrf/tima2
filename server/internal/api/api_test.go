package api

// Интеграционные тесты Auth + Message Service с реальным PostgreSQL (dev-compose).
// Пропускаются, если база недоступна. Полный производственный поток:
// SMS-код → регистрация устройства → device JWT → конверт → история → чтение.
// Криптография настоящая, тем же конвейером, что клиентский messenger-crypto.

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

	"tima/server/internal/auth"
	timacrypto "tima/server/internal/crypto"
	pb "tima/server/internal/proto"
	"tima/server/internal/store"
	"tima/server/migrations"
)

const chatID = "aaaaaaaa-0000-0000-0000-00000000c4a7"

// device — «клиентское устройство»: ключи + выданные сервером идентичность и токен.
type device struct {
	userID  string
	id      string
	token   string
	encPriv [32]byte
	encPub  [32]byte
	signKey ed25519.PrivateKey
}

func setup(t *testing.T) (*httptest.Server, *Server) {
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
	srv := &Server{Store: st, Auth: auth.NewIssuer([]byte("test-signing-key")), DevSMS: true}
	srv.Register(mux)
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	return ts, srv
}

func postJSON(t *testing.T, ts *httptest.Server, path string, body any, out any) int {
	t.Helper()
	raw, _ := json.Marshal(body)
	resp, err := http.Post(ts.URL+path, "application/json", bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	}
	return resp.StatusCode
}

// registerDevice проходит полный auth-поток: sms/request → verify → register.
func registerDevice(t *testing.T, ts *httptest.Server, phone string) *device {
	t.Helper()
	d := &device{}
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

	var smsResp struct {
		RequestID string `json:"request_id"`
		DevCode   string `json:"dev_code"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": phone}, &smsResp); code != 200 {
		t.Fatalf("sms/request: %d", code)
	}
	if smsResp.DevCode == "" {
		t.Fatal("dev_code пуст — DevSMS должен быть включён в тестах")
	}
	var verifyResp struct {
		RegistrationToken string `json:"registration_token"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/verify",
		map[string]string{"request_id": smsResp.RequestID, "code": smsResp.DevCode}, &verifyResp); code != 200 {
		t.Fatalf("sms/verify: %d", code)
	}
	b64 := base64.RawURLEncoding
	var regResp struct {
		UserID      string `json:"user_id"`
		DeviceID    string `json:"device_id"`
		AccessToken string `json:"access_token"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/register", map[string]string{
		"registration_token": verifyResp.RegistrationToken,
		"encryption_pub":     b64.EncodeToString(d.encPub[:]),
		"signing_pub":        b64.EncodeToString(d.signKey.Public().(ed25519.PublicKey)),
	}, &regResp); code != 201 {
		t.Fatalf("register: %d", code)
	}
	d.userID, d.id, d.token = regResp.UserID, regResp.DeviceID, regResp.AccessToken
	return d
}

// sealEnvelope повторяет клиентский конвейер (crypto-protocol.md §3.2) на Go.
func sealEnvelope(t *testing.T, sender *device, recipients []*device, messageID uint64, plaintext []byte) *pb.Envelope {
	t.Helper()
	var messageKey [32]byte
	if _, err := rand.Read(messageKey[:]); err != nil {
		t.Fatal(err)
	}
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		t.Fatal(err)
	}
	payload := secretbox.Seal(nonce[:], plaintext, &nonce, &messageKey) // слой 1

	escrowCt := bytes.Repeat([]byte{0xEC}, 1088) // слой 2: серверу проверяем только размеры
	escrowWrapped := bytes.Repeat([]byte{0xED}, 24+16+32)

	ephPub, ephPriv, err := box.GenerateKey(rand.Reader) // слой 4
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
		SenderId:        sender.userID,
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
		MessageID: messageID, ChatID: chatID, SenderID: sender.userID, SenderDevice: sender.id,
		Kind: uint32(meta.Kind), CreatedAtUnixMs: meta.CreatedAtUnixMs,
	}, payload, append(append([]byte{}, escrowCt...), escrowWrapped...), ephPub[:], nil)
	env.Signature = ed25519.Sign(sender.signKey, cb)
	return env
}

func post(t *testing.T, ts *httptest.Server, env *pb.Envelope, bearer, clientMsgID string) *http.Response {
	t.Helper()
	raw, err := proto.Marshal(env)
	if err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequest("POST", ts.URL+"/api/v1/messages", bytes.NewReader(raw))
	req.Header.Set("Content-Type", "application/x-protobuf")
	req.Header.Set("Authorization", "Bearer "+bearer)
	req.Header.Set("X-Client-Msg-Id", clientMsgID)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	return resp
}

func TestAuthAndMessagingEndToEnd(t *testing.T) {
	ts, _ := setup(t)
	sender := registerDevice(t, ts, "+79990000001")
	recipient := registerDevice(t, ts, "+79990000002")

	// Мультиустройство: повторный вход с тем же телефоном → новое устройство того же пользователя
	senderPhone2 := registerDevice(t, ts, "+79990000001")
	if senderPhone2.userID != sender.userID {
		t.Fatal("повторная регистрация телефона должна добавлять устройство тому же пользователю")
	}
	if senderPhone2.id == sender.id {
		t.Fatal("device_id обязаны различаться")
	}

	// /keys/devices: отправитель видит устройства получателя для обёрток
	req, _ := http.NewRequest("GET", ts.URL+"/api/v1/keys/devices?user_id="+recipient.userID, nil)
	req.Header.Set("Authorization", "Bearer "+sender.token)
	keysResp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	var keys struct {
		Devices []struct {
			DeviceID      string `json:"device_id"`
			EncryptionPub string `json:"encryption_pub"`
		} `json:"devices"`
	}
	_ = json.NewDecoder(keysResp.Body).Decode(&keys)
	keysResp.Body.Close()
	if len(keys.Devices) != 1 || keys.Devices[0].DeviceID != recipient.id {
		t.Fatalf("/keys/devices: ожидалось устройство получателя, получено %+v", keys.Devices)
	}

	// Отправка: обёртки получателю и второму устройству отправителя
	plaintext := []byte("Сквозной тест TIMA с настоящим auth 🚀")
	env := sealEnvelope(t, sender, []*device{recipient, senderPhone2}, 1001, plaintext)
	const clientMsgID = "eeeeeeee-0000-0000-0000-000000001001"
	resp := post(t, ts, env, sender.token, clientMsgID)
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("POST /messages: %d", resp.StatusCode)
	}
	resp.Body.Close()

	// Повтор → дедупликация
	resp = post(t, ts, env, sender.token, clientMsgID)
	var dup struct {
		Duplicate bool `json:"duplicate"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&dup)
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK || !dup.Duplicate {
		t.Fatalf("повторный POST: ожидался duplicate=true, статус %d", resp.StatusCode)
	}

	// История получателя: устройство определяется токеном
	histReq, _ := http.NewRequest("GET", fmt.Sprintf("%s/api/v1/chats/%s/messages", ts.URL, chatID), nil)
	histReq.Header.Set("Authorization", "Bearer "+recipient.token)
	histResp, err := http.DefaultClient.Do(histReq)
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
	if len(hist.Messages) != 1 {
		t.Fatalf("в истории %d сообщений, ожидалось 1", len(hist.Messages))
	}

	// «Получатель»: конверт → wrapped_key → message_key → plaintext
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

func TestRejections(t *testing.T) {
	ts, _ := setup(t)
	sender := registerDevice(t, ts, "+79990000003")
	recipient := registerDevice(t, ts, "+79990000004")

	// Без токена → 401
	env := sealEnvelope(t, sender, []*device{recipient}, 2000, []byte("тайна"))
	raw, _ := proto.Marshal(env)
	resp, err := http.Post(ts.URL+"/api/v1/messages", "application/x-protobuf", bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("без токена: ожидался 401, получен %d", resp.StatusCode)
	}

	// Чужой токен (конверт отправителя, токен получателя) → 403 ещё до подписи
	resp = post(t, ts, env, recipient.token, "eeeeeeee-0000-0000-0000-000000002000")
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("чужой токен: ожидался 403, получен %d", resp.StatusCode)
	}

	// Повреждённая подпись → 403
	env1 := sealEnvelope(t, sender, []*device{recipient}, 2001, []byte("тайна"))
	env1.Signature[0] ^= 1
	resp = post(t, ts, env1, sender.token, "eeeeeeee-0000-0000-0000-000000002001")
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("повреждённая подпись: ожидался 403, получен %d", resp.StatusCode)
	}

	// Подмена метаданных после подписи → 403 (sender_mismatch)
	env2 := sealEnvelope(t, sender, []*device{recipient}, 2002, []byte("тайна"))
	env2.Meta.SenderId = recipient.userID
	resp = post(t, ts, env2, sender.token, "eeeeeeee-0000-0000-0000-000000002002")
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("подмена sender_id: ожидался 403, получен %d", resp.StatusCode)
	}

	// Повторное использование SMS-кода → 403
	var smsResp struct {
		RequestID string `json:"request_id"`
		DevCode   string `json:"dev_code"`
	}
	postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": "+79990000005"}, &smsResp)
	var verifyResp struct {
		RegistrationToken string `json:"registration_token"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/verify",
		map[string]string{"request_id": smsResp.RequestID, "code": smsResp.DevCode}, &verifyResp); code != 200 {
		t.Fatalf("sms/verify: %d", code)
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/verify",
		map[string]string{"request_id": smsResp.RequestID, "code": smsResp.DevCode}, nil); code != http.StatusForbidden {
		t.Fatalf("повторный verify: ожидался 403, получен %d", code)
	}

	// Неверный код → 403
	postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": "+79990000006"}, &smsResp)
	if code := postJSON(t, ts, "/api/v1/auth/sms/verify",
		map[string]string{"request_id": smsResp.RequestID, "code": "000000"}, nil); code != http.StatusForbidden {
		// вероятность коллизии с настоящим кодом 1e-6 — приемлемо для теста
		t.Fatalf("неверный код: ожидался 403, получен %d", code)
	}
}
