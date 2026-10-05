package api

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"os"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5"
)

// TestStartAnewBan — ДУ10 (Р36, Р41): владелец закрывает «Начать заново» фразой и SMS, и после
// этого его номер не форкается по одной SMS; закрытие видно в профиле и в отказе входа, а
// снять его нельзя даже прямым запросом к базе.
func TestStartAnewBan(t *testing.T) {
	ts, _ := setup(t)
	phone := "+79990060077"
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	owner, code, _ := registerRaw(t, ts, phone, pub, false)
	if code != 201 {
		t.Fatalf("регистрация: %d", code)
	}
	var me struct {
		Banned bool `json:"start_anew_banned"`
	}
	if code := getAuthed(t, ts, owner.token, "/api/v1/users/me", &me); code != 200 || me.Banned {
		t.Fatalf("до запрета: %d banned=%v", code, me.Banned)
	}
	otherPub, _, _ := ed25519.GenerateKey(rand.Reader)
	if _, code, body := registerReply(t, ts, phone, otherPub, false); code != 403 || !strings.Contains(string(body), `"start_anew":true`) {
		t.Fatalf("отказ до запрета обязан разрешать «Начать заново»: %d %s", code, body)
	}

	// Запрет: SMS на номер аккаунта и подпись вызова ключом из фразы.
	var sms struct {
		RequestID string `json:"request_id"`
		DevCode   string `json:"dev_code"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": phone}, &sms); code != 200 {
		t.Fatalf("sms/request: %d", code)
	}
	var verified struct {
		RegistrationToken string `json:"registration_token"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/verify", map[string]string{"request_id": sms.RequestID, "code": sms.DevCode}, &verified); code != 200 {
		t.Fatalf("sms/verify: %d", code)
	}
	challenge := challengeFor(t, ts, owner.token)
	b64 := base64.RawURLEncoding
	// Чужая фраза запрет не ставит.
	_, wrongPriv, _ := ed25519.GenerateKey(rand.Reader)
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/start-anew-ban", owner.token, map[string]string{
		"registration_token": verified.RegistrationToken, "challenge_token": challenge,
		"signature": b64.EncodeToString(ed25519.Sign(wrongPriv, []byte(challenge))),
	}, nil); code != 403 {
		t.Fatalf("чужая фраза: %d, ждали 403", code)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/start-anew-ban", owner.token, map[string]string{
		"registration_token": verified.RegistrationToken, "challenge_token": challenge,
		"signature": b64.EncodeToString(ed25519.Sign(priv, []byte(challenge))),
	}, nil); code != 200 {
		t.Fatalf("запрет: %d", code)
	}

	if code := getAuthed(t, ts, owner.token, "/api/v1/users/me", &me); code != 200 || !me.Banned {
		t.Fatalf("после запрета: %d banned=%v", code, me.Banned)
	}
	if _, code, body := registerReply(t, ts, phone, otherPub, false); code != 403 || !strings.Contains(string(body), `"start_anew":false`) {
		t.Fatalf("отказ после запрета обязан закрывать «Начать заново»: %d %s", code, body)
	}
	if _, code, body := registerRaw(t, ts, phone, otherPub, true); code != 403 || !strings.Contains(body, "start_anew_banned") {
		t.Fatalf("«Начать заново» после запрета: %d %s, ждали 403 start_anew_banned", code, body)
	}

	// Снять нельзя и в обход ручек: держит триггер 0064.
	url := os.Getenv("TIMA_TEST_DATABASE_URL")
	if url == "" {
		url = "postgres://tima:tima-dev-only@localhost:5432/tima_test"
	}
	conn, err := pgx.Connect(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(context.Background())
	if _, err := conn.Exec(context.Background(),
		`UPDATE persons SET start_anew_banned = false WHERE start_anew_banned`); err == nil {
		t.Fatal("запрет снялся прямым запросом — триггер не держит")
	}
}
