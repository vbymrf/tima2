package api

// Передача виртуального аккаунта (ПЛАН-КОНТАКТОВ.md, Д12).
//
// Проверяется то, ради чего передача устроена именно так: кода мало без фразы, фразы мало
// без кода, три неверные попытки гасят код, а после передачи прежний владелец теряет
// аккаунт вместе со всеми своими устройствами в нём.

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/crypto/curve25519"
)

// виртуальныйСФразой — заводит виртуальный аккаунт и возвращает его ключ личности.
//
// Ключ здесь и есть «фраза»: клиент выводит его из неё, а сервер фразы не видел.
func виртуальныйСФразой(
	t *testing.T, ts *httptest.Server, owner *владелец, nick string,
) (string, ed25519.PrivateKey) {
	t.Helper()
	seed := make([]byte, ed25519.SeedSize)
	if _, err := rand.Read(seed); err != nil {
		t.Fatal(err)
	}
	identity := ed25519.NewKeyFromSeed(seed)
	pub := identity.Public().(ed25519.PublicKey)

	var encPriv [32]byte
	if _, err := rand.Read(encPriv[:]); err != nil {
		t.Fatal(err)
	}
	encPub, err := curve25519.X25519(encPriv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	signPub, _, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}

	b64 := base64.RawURLEncoding
	var resp struct {
		UserID string `json:"user_id"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/users/me/virtuals", owner.token, map[string]any{
		"nickname":       nick,
		"identity_pub":   b64.EncodeToString(pub),
		"signature":      b64.EncodeToString(ed25519.Sign(owner.identity, virtualSigned(nick, pub))),
		"encryption_pub": b64.EncodeToString(encPub),
		"signing_pub":    b64.EncodeToString(signPub),
	}, &resp)
	if code != http.StatusCreated {
		t.Fatalf("создание виртуального: %d", code)
	}
	return resp.UserID, identity
}

func началПередачу(t *testing.T, ts *httptest.Server, owner *владелец, virtualID string) (int, []byte) {
	t.Helper()
	var resp struct {
		Code string `json:"code"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/users/me/virtuals/"+virtualID+"/transfer",
		owner.token, nil, &resp)
	raw, _ := base64.RawURLEncoding.DecodeString(resp.Code)
	return code, raw
}

func принял(t *testing.T, ts *httptest.Server, token string, code []byte, identity ed25519.PrivateKey) int {
	t.Helper()
	b64 := base64.RawURLEncoding
	return authedJSON(t, ts, "POST", "/api/v1/transfers/accept", token, map[string]any{
		"code":  b64.EncodeToString(code),
		"proof": b64.EncodeToString(ed25519.Sign(identity, code)),
	}, nil)
}

func TestПередачаТребуетИКодаИФразы(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000071")
	анна := заведиВладельца(t, ts, "+79990000072")
	virtualID, фраза := виртуальныйСФразой(t, ts, пётр, "peredavaemyy_1")

	// Одной фразы мало: без кода передачи предъявлять нечего.
	чужойКод := make([]byte, 32)
	if _, err := rand.Read(чужойКод); err != nil {
		t.Fatal(err)
	}
	if code := принял(t, ts, анна.token, чужойКод, фраза); code != http.StatusNotFound {
		t.Fatalf("приняли по выдуманному коду: %d", code)
	}

	status, код := началПередачу(t, ts, пётр, virtualID)
	if status != http.StatusCreated || len(код) != 32 {
		t.Fatalf("начало передачи: %d, код %d байт", status, len(код))
	}

	// Одного кода мало: без фразы он ничего не передаёт.
	_, чужая, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	if code := принял(t, ts, анна.token, код, чужая); code != http.StatusForbidden {
		t.Fatalf("приняли с чужой фразой: %d", code)
	}

	// И код, и фраза — аккаунт переходит.
	if code := принял(t, ts, анна.token, код, фраза); code != http.StatusOK {
		t.Fatalf("передача не прошла: %d", code)
	}
	if мои := моиВиртуальные(t, ts, пётр.token); len(мои) != 0 {
		t.Fatalf("аккаунт остался у прежнего владельца: %v", мои)
	}
	if мои := моиВиртуальные(t, ts, анна.token); len(мои) != 1 || мои[0] != virtualID {
		t.Fatalf("аккаунт не пришёл новому владельцу: %v", мои)
	}
}

func TestТриНеверныеПопыткиГасятКод(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000073")
	анна := заведиВладельца(t, ts, "+79990000074")
	virtualID, фраза := виртуальныйСФразой(t, ts, пётр, "sgoraemyy_kod_1")

	_, код := началПередачу(t, ts, пётр, virtualID)
	_, чужая, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 3; i++ {
		if code := принял(t, ts, анна.token, код, чужая); code != http.StatusForbidden {
			t.Fatalf("попытка %d: %d", i, code)
		}
	}
	// Код сожжён: даже правильная фраза его больше не оживит — нужен новый код, а
	// выдаёт его только прежний владелец.
	if code := принял(t, ts, анна.token, код, фраза); code != http.StatusNotFound {
		t.Fatalf("сожжённый код принял правильную фразу: %d", code)
	}
	if мои := моиВиртуальные(t, ts, пётр.token); len(мои) != 1 {
		t.Fatalf("аккаунт ушёл, хотя передача не состоялась: %v", мои)
	}
}

func TestОтменённаяПередачаНеПринимается(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000075")
	анна := заведиВладельца(t, ts, "+79990000076")
	virtualID, фраза := виртуальныйСФразой(t, ts, пётр, "otmenennaya_11")

	_, код := началПередачу(t, ts, пётр, virtualID)
	if code := authedJSON(t, ts, "DELETE", "/api/v1/users/me/virtuals/"+virtualID+"/transfer",
		пётр.token, nil, nil); code != http.StatusNoContent {
		t.Fatalf("отмена: %d", code)
	}
	if code := принял(t, ts, анна.token, код, фраза); code != http.StatusNotFound {
		t.Fatalf("отменённый код сработал: %d", code)
	}
}

func TestЧужойАккаунтНеПередать(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000077")
	анна := заведиВладельца(t, ts, "+79990000078")
	virtualID, _ := виртуальныйСФразой(t, ts, пётр, "ne_tvoy_akkaunt")

	// Анна пытается передать чужой виртуальный аккаунт.
	if code, _ := началПередачу(t, ts, анна, virtualID); code != http.StatusForbidden {
		t.Fatalf("чужой аккаунт удалось поставить на передачу: %d", code)
	}
}

func TestНоваяПередачаГаситПрежнюю(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000079")
	анна := заведиВладельца(t, ts, "+79990000080")
	virtualID, фраза := виртуальныйСФразой(t, ts, пётр, "peredumal_1234")

	_, первый := началПередачу(t, ts, пётр, virtualID)
	_, второй := началПередачу(t, ts, пётр, virtualID)

	// Владелец передумал, кому передавать. Прежний код умер вместе с решением —
	// иначе аккаунт был бы обещан двоим и достался тому, кто быстрее.
	if code := принял(t, ts, анна.token, первый, фраза); code != http.StatusNotFound {
		t.Fatalf("прежний код всё ещё действует: %d", code)
	}
	if code := принял(t, ts, анна.token, второй, фраза); code != http.StatusOK {
		t.Fatalf("новый код не сработал: %d", code)
	}
}
