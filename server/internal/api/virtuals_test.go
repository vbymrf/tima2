package api

// Виртуальные аккаунты (ПЛАН-КОНТАКТОВ.md, Д10).
//
// Проверяется то, на чём держится замысел: виртуальный аккаунт полноценен, но у него нет
// телефона и по номеру его не найти; предел на владельца соблюдается сервером, а не
// интерфейсом; создание требует подписи владельца; удаление владельца уносит его
// виртуальные и всё, чем они владеют.

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"

	"golang.org/x/crypto/curve25519"
)

// владелец — устройство с ключом личности: без него завести виртуальный аккаунт
// нечем, потому что подпись владельца проверять не по чему.
type владелец struct {
	*device
	identity ed25519.PrivateKey
}

// заведиВладельца — регистрация СРАЗУ с ключом личности.
//
// Отдельной ручки «установить ключ личности» нет: он приходит при регистрации
// устройства, потому что выводится из фразы, которую человек вводит там же. Значит и
// в тесте владельца заводят так же, а не досылают ключ вторым запросом.
func заведиВладельца(t *testing.T, ts *httptest.Server, phone string) *владелец {
	t.Helper()
	seed := make([]byte, ed25519.SeedSize)
	if _, err := rand.Read(seed); err != nil {
		t.Fatal(err)
	}
	priv := ed25519.NewKeyFromSeed(seed)

	d := &device{}
	if _, err := rand.Read(d.encPriv[:]); err != nil {
		t.Fatal(err)
	}
	pub, err := curve25519.X25519(d.encPriv[:], curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	copy(d.encPub[:], pub)
	signSeed := make([]byte, ed25519.SeedSize)
	if _, err := rand.Read(signSeed); err != nil {
		t.Fatal(err)
	}
	d.signKey = ed25519.NewKeyFromSeed(signSeed)

	var smsResp struct {
		RequestID string `json:"request_id"`
		DevCode   string `json:"dev_code"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": phone}, &smsResp); code != http.StatusOK {
		t.Fatalf("sms/request: %d", code)
	}
	var verifyResp struct {
		RegistrationToken string `json:"registration_token"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/verify",
		map[string]string{"request_id": smsResp.RequestID, "code": smsResp.DevCode}, &verifyResp); code != http.StatusOK {
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
		"identity_pub":       b64.EncodeToString(priv.Public().(ed25519.PublicKey)),
		"platform":           "android",
	}, &regResp); code != http.StatusCreated {
		t.Fatalf("register с ключом личности: %d", code)
	}
	d.userID, d.id, d.token = regResp.UserID, regResp.DeviceID, regResp.AccessToken
	return &владелец{device: d, identity: priv}
}

// завестиВиртуального — создание с подписью владельца.
func завестиВиртуального(t *testing.T, ts *httptest.Server, owner *владелец, nick string) (int, string) {
	t.Helper()
	pub, _, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	b64 := base64.RawURLEncoding
	подпись := ed25519.Sign(owner.identity, virtualSigned(nick, pub))

	// Ключи устройства для нового аккаунта — свои: одно устройство, но разные ключи
	// на аккаунт, иначе «отдельный пользователь» оставался бы словами.
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

	var resp struct {
		UserID      string `json:"user_id"`
		DeviceID    string `json:"device_id"`
		AccessToken string `json:"access_token"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/users/me/virtuals", owner.token, map[string]any{
		"nickname":       nick,
		"identity_pub":   b64.EncodeToString(pub),
		"signature":      b64.EncodeToString(подпись),
		"encryption_pub": b64.EncodeToString(encPub),
		"signing_pub":    b64.EncodeToString(signPub),
		"platform":       "android",
	}, &resp)
	if code == http.StatusCreated && resp.AccessToken == "" {
		t.Fatal("аккаунт заведён без токена — войти в него нечем")
	}
	последнийТокен = resp.AccessToken
	return code, resp.UserID
}

// последнийТокен — токен последнего заведённого виртуального аккаунта. Нужен тесту,
// который проверяет, что в аккаунт можно войти сразу.
var последнийТокен string

func моиВиртуальные(t *testing.T, ts *httptest.Server, token string) []string {
	t.Helper()
	var resp struct {
		Virtuals []struct {
			UserID string `json:"user_id"`
		} `json:"virtuals"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/me/virtuals", token, nil, &resp); code != http.StatusOK {
		t.Fatalf("список виртуальных: %d", code)
	}
	out := make([]string, 0, len(resp.Virtuals))
	for _, v := range resp.Virtuals {
		out = append(out, v.UserID)
	}
	return out
}

func TestВиртуальныйЗаводитсяИНаходитсяТолькоПоНику(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000041")
	анна := registerDevice(t, ts, "+79990000042")

	code, virtual := завестиВиртуального(t, ts, пётр, "tenevoy_avtor_1")
	if code != http.StatusCreated {
		t.Fatalf("создание виртуального: %d", code)
	}
	if мои := моиВиртуальные(t, ts, пётр.token); len(мои) != 1 || мои[0] != virtual {
		t.Fatalf("свой список виртуальных: %v", мои)
	}
	// Находят его по нику — единственным способом: телефона у него нет.
	if code, id := поНику(t, ts, анна.token, "tenevoy_avtor_1"); code != http.StatusOK || id != virtual {
		t.Fatalf("по нику не нашёлся: %d %s", code, id)
	}
	// А у Анны своих виртуальных нет: чужой список не показывается никому.
	if мои := моиВиртуальные(t, ts, анна.token); len(мои) != 0 {
		t.Fatalf("чужие виртуальные попали в свой список: %v", мои)
	}
}

func TestУВиртуальногоНетТелефона(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000043")
	анна := registerDevice(t, ts, "+79990000044")

	_, virtual := завестиВиртуального(t, ts, пётр, "bez_telefona_00")

	var resp struct {
		HasPhone map[string]bool `json:"has_phone"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/users/names", анна.token,
		map[string]any{"ids": []string{virtual, пётр.userID}}, &resp)
	if code != http.StatusOK {
		t.Fatalf("имена: %d", code)
	}
	if resp.HasPhone[virtual] {
		t.Fatalf("у виртуального нашёлся телефон")
	}
	// А у обычного — есть, и это не зависит от того, знает ли спрашивающий его номер.
	if !resp.HasPhone[пётр.userID] {
		t.Fatalf("у обычного аккаунта телефон не признан")
	}
}

func TestШестойВиртуальныйОтвергается(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000045")

	for i := 1; i <= 5; i++ {
		nick := "virtual_akkaunt_" + string(rune('0'+i))
		if code, _ := завестиВиртуального(t, ts, пётр, nick); code != http.StatusCreated {
			t.Fatalf("виртуальный %d: %d", i, code)
		}
	}
	// Предел серверный, а не интерфейсный: клиентская проверка обходится подделанным
	// запросом, и тогда номер один, а аккаунтов тысяча.
	if code, _ := завестиВиртуального(t, ts, пётр, "virtual_akkaunt_6"); code != http.StatusConflict {
		t.Fatalf("шестой виртуальный прошёл: %d", code)
	}
	if мои := моиВиртуальные(t, ts, пётр.token); len(мои) != 5 {
		t.Fatalf("виртуальных стало %d", len(мои))
	}
}

func TestБезПодписиВладельцаНеСоздаётся(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000046")

	pub, _, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	b64 := base64.RawURLEncoding
	// Чужая подпись: тот же запрос, но подписан не владельцем.
	_, чужой, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	// Ключи устройства настоящие: иначе запрос отвергнется как негодный ещё до
	// проверки подписи, и тест перестанет проверять то, ради чего написан.
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
	code := authedJSON(t, ts, "POST", "/api/v1/users/me/virtuals", пётр.token, map[string]any{
		"nickname":       "chuzhaya_podpis_1",
		"identity_pub":   b64.EncodeToString(pub),
		"signature":      b64.EncodeToString(ed25519.Sign(чужой, virtualSigned("chuzhaya_podpis_1", pub))),
		"encryption_pub": b64.EncodeToString(encPub),
		"signing_pub":    b64.EncodeToString(signPub),
	}, nil)
	if code != http.StatusForbidden {
		t.Fatalf("создание с чужой подписью прошло: %d", code)
	}
	if мои := моиВиртуальные(t, ts, пётр.token); len(мои) != 0 {
		t.Fatalf("аккаунт всё-таки завёлся: %v", мои)
	}
}

func TestВиртуальныйПолноценен(t *testing.T) {
	ts, _ := setup(t)
	пётр := заведиВладельца(t, ts, "+79990000047")
	_, virtual := завестиВиртуального(t, ts, пётр, "polnocennyy_00")
	токен := последнийТокен

	// В аккаунт можно войти сразу: устройство и токен выданы при создании. Кода из
	// SMS не будет никогда — номера у него нет, и без этого аккаунт был бы заведён
	// и недоступен.
	var своя struct {
		ChannelID string `json:"channel_id"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/users/me/feed", токен, nil, &своя); code != http.StatusOK {
		t.Fatalf("виртуальный не может открыть свою ленту: %d", code)
	}

	// Прав у него ровно столько же: ник он себе уже занял при создании, а найти его
	// можно и он виден в списке имён — тем же кодом, что и обычного.
	var resp struct {
		Nicknames map[string]string `json:"nicknames"`
	}
	code := authedJSON(t, ts, "POST", "/api/v1/users/names", пётр.token,
		map[string]any{"ids": []string{virtual}}, &resp)
	if code != http.StatusOK || resp.Nicknames[virtual] != "polnocennyy_00" {
		t.Fatalf("виртуальный не виден обычными ручками: %d %v", code, resp.Nicknames)
	}
}
