package api

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	timacrypto "tima/server/internal/crypto"
)

// ── Доверие к устройствам (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ ДУ1–ДУ3) ───────────────────────
//
// Беда «вор SIM читает новые сообщения»: до ДУ2 вор с перевыпущенной SIM заводил устройство в
// чужом аккаунте — и без фразы, и с открытым ключом личности жертвы. Эти проверки — та же проба,
// что в записи беды, только с ожидаемым отказом.

type trustAccount struct {
	identity ed25519.PrivateKey
	ask      ed25519.PrivateKey
}

func newTrustAccount() trustAccount {
	_, id, _ := ed25519.GenerateKey(rand.Reader)
	_, ask, _ := ed25519.GenerateKey(rand.Reader)
	return trustAccount{identity: id, ask: ask}
}

func (a trustAccount) identityPub() []byte { return a.identity.Public().(ed25519.PublicKey) }
func (a trustAccount) askPub() []byte      { return a.ask.Public().(ed25519.PublicKey) }

// registerProof — регистрация с полями доверия. extra дописывается в тело как есть.
func registerProof(t *testing.T, ts *httptest.Server, phone string, identityPub []byte, extra func(enc, sig []byte) map[string]any) (*device, int, string) {
	t.Helper()
	d := &device{}
	seed := make([]byte, 32)
	_, _ = rand.Read(seed)
	d.signKey = ed25519.NewKeyFromSeed(seed)
	encPub := make([]byte, 32)
	_, _ = rand.Read(encPub)
	copy(d.encPub[:], encPub)
	var smsResp struct {
		RequestID string `json:"request_id"`
		DevCode   string `json:"dev_code"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/request", map[string]string{"phone": phone}, &smsResp); code != 200 {
		t.Fatalf("sms/request: %d", code)
	}
	var verifyResp struct {
		RegistrationToken string `json:"registration_token"`
	}
	if code := postJSON(t, ts, "/api/v1/auth/sms/verify",
		map[string]string{"request_id": smsResp.RequestID, "code": smsResp.DevCode}, &verifyResp); code != 200 {
		t.Fatalf("sms/verify: %d", code)
	}
	b64 := base64.RawURLEncoding
	sigPub := d.signKey.Public().(ed25519.PublicKey)
	body := map[string]any{
		"registration_token": verifyResp.RegistrationToken,
		"encryption_pub":     b64.EncodeToString(encPub),
		"signing_pub":        b64.EncodeToString(sigPub),
		"platform":           "android",
	}
	if len(identityPub) > 0 {
		body["identity_pub"] = b64.EncodeToString(identityPub)
	}
	if extra != nil {
		for k, v := range extra(encPub, sigPub) {
			body[k] = v
		}
	}
	var raw json.RawMessage
	code := postJSON(t, ts, "/api/v1/auth/register", body, &raw)
	var reg struct {
		UserID      string `json:"user_id"`
		DeviceID    string `json:"device_id"`
		AccessToken string `json:"access_token"`
		Code        string `json:"code"`
	}
	_ = json.Unmarshal(raw, &reg)
	d.userID, d.id, d.token = reg.UserID, reg.DeviceID, reg.AccessToken
	return d, code, reg.Code
}

// withAsk — телефон с фразой: заводит КПУ и заверяет им себя.
func (a trustAccount) withAsk(enc, sig []byte) map[string]any {
	b64 := base64.RawURLEncoding
	return map[string]any{
		"ask_pub":         b64.EncodeToString(a.askPub()),
		"ask_sig":         b64.EncodeToString(ed25519.Sign(a.identity, timacrypto.AskCertBytes(a.askPub()))),
		"device_cert_by":  "ask",
		"device_cert_sig": b64.EncodeToString(ed25519.Sign(a.ask, timacrypto.DeviceCertBytes(enc, sig))),
	}
}

// byPhrase — устройство заверено прямо ключом личности (ПК, вошедший по фразе).
func (a trustAccount) byPhrase(enc, sig []byte) map[string]any {
	return map[string]any{
		"device_cert_by":  "identity",
		"device_cert_sig": base64.RawURLEncoding.EncodeToString(ed25519.Sign(a.identity, timacrypto.DeviceCertBytes(enc, sig))),
	}
}

type keysView struct {
	IdentityPub string `json:"identity_pub"`
	TrustMode   string `json:"trust_mode"`
	SigningKeys []struct {
		AskID  string `json:"ask_id"`
		AskPub string `json:"ask_pub"`
		AskSig string `json:"ask_sig"`
	} `json:"signing_keys"`
	Devices []struct {
		DeviceID  string `json:"device_id"`
		CertBy    string `json:"cert_by"`
		CertAskID string `json:"cert_ask_id"`
		CertSig   string `json:"cert_sig"`
	} `json:"devices"`
}

func keysOf(t *testing.T, ts *httptest.Server, viewer *device, userID string) keysView {
	t.Helper()
	var v keysView
	if code := getAuthed(t, ts, viewer.token, "/api/v1/keys/devices?user_id="+userID, &v); code != 200 {
		t.Fatalf("/keys/devices: %d", code)
	}
	return v
}

func TestSimThiefRefusedInRequireMode(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRequire
	phone := "+79990000101"
	victim := newTrustAccount()
	owner, code, why := registerProof(t, ts, phone, victim.identityPub(), victim.withAsk)
	if code != 201 {
		t.Fatalf("хозяин с фразой и КПУ: ожидался 201, получен %d %s", code, why)
	}
	// Вор без фразы.
	if _, code, why := registerProof(t, ts, phone, nil, nil); code != http.StatusForbidden || why != "device_unproven" {
		t.Fatalf("вор без фразы: ожидался 403 device_unproven, получен %d %s", code, why)
	}
	// Вор с открытым ключом личности жертвы — тот, что сервер отдаёт любому.
	if _, code, why := registerProof(t, ts, phone, victim.identityPub(), nil); code != http.StatusForbidden || why != "device_unproven" {
		t.Fatalf("вор с открытым ключом: ожидался 403 device_unproven, получен %d %s", code, why)
	}
	// Вор со своим КПУ, будто заверенным: свидетельство КПУ подписано не ключом жертвы.
	thief := newTrustAccount()
	forged := func(enc, sig []byte) map[string]any {
		m := thief.withAsk(enc, sig)
		return m
	}
	if _, code, why := registerProof(t, ts, phone, victim.identityPub(), forged); code != http.StatusForbidden || why != "device_unproven" {
		t.Fatalf("вор с поддельным КПУ: ожидался 403 device_unproven, получен %d %s", code, why)
	}
	friend, code, _ := registerProof(t, ts, "+79990000102", newTrustAccount().identityPub(), nil)
	_ = code
	// В строгом режиме друг без доказательства не заведён — смотрим глазами самого хозяина.
	if friend.token == "" {
		friend = owner
	}
	v := keysOf(t, ts, friend, owner.userID)
	if len(v.Devices) != 1 || v.Devices[0].DeviceID != owner.id {
		t.Fatalf("собеседник должен видеть одно устройство хозяина, видит %+v", v.Devices)
	}
	if v.Devices[0].CertBy != "ask" || len(v.SigningKeys) != 1 || v.Devices[0].CertAskID != v.SigningKeys[0].AskID {
		t.Fatalf("свидетельство хозяина не отдано: %+v %+v", v.Devices, v.SigningKeys)
	}
	if v.IdentityPub != base64.RawURLEncoding.EncodeToString(victim.identityPub()) || v.TrustMode != trustRequire {
		t.Fatalf("ключ личности или режим не отданы: %+v", v)
	}
	// Второе устройство хозяина по фразе (ПК) — проходит.
	if _, code, why := registerProof(t, ts, phone, victim.identityPub(), victim.byPhrase); code != 201 {
		t.Fatalf("ПК по фразе: ожидался 201, получен %d %s", code, why)
	}
}

func TestRecordModeLetsThroughButStaysUnsigned(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRecord
	phone := "+79990000103"
	victim := newTrustAccount()
	owner, code, _ := registerProof(t, ts, phone, victim.identityPub(), victim.withAsk)
	if code != 201 {
		t.Fatalf("хозяин: %d", code)
	}
	thief, code, _ := registerProof(t, ts, phone, nil, nil)
	if code != 201 {
		t.Fatalf("в режиме record вор проходит (пишется в журнал), получен %d", code)
	}
	v := keysOf(t, ts, owner, owner.userID)
	for _, d := range v.Devices {
		if d.DeviceID == thief.id && d.CertBy != "" {
			t.Fatalf("устройство без доказательства не может быть заверено: %+v", d)
		}
	}
}

func TestRequireRefusesAccountWithoutPhrase(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRequire
	if _, code, why := registerProof(t, ts, "+79990000104", nil, nil); code != http.StatusForbidden || why != "phrase_required" {
		t.Fatalf("аккаунт без фразы: ожидался 403 phrase_required, получен %d %s", code, why)
	}
}

func TestPhoneIssuesSigningKeyAndCertifiesOwnDevice(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRecord
	phone := "+79990000105"
	acc := newTrustAccount()
	// Оба устройства заведены «до ДУ1»: без свидетельств.
	phoneDev, _, _ := registerProof(t, ts, phone, acc.identityPub(), nil)
	pcDev, _, _ := registerProof(t, ts, phone, acc.identityPub(), nil)
	b64 := base64.RawURLEncoding
	encOf := func(d *device) []byte { return d.encPub[:] }
	sigOf := func(d *device) []byte { return d.signKey.Public().(ed25519.PublicKey) }

	// Фраза не та: свидетельство КПУ подписано чужим ключом личности.
	wrong := newTrustAccount()
	bad := map[string]any{
		"ask_pub":         b64.EncodeToString(acc.askPub()),
		"ask_sig":         b64.EncodeToString(ed25519.Sign(wrong.identity, timacrypto.AskCertBytes(acc.askPub()))),
		"device_cert_sig": b64.EncodeToString(ed25519.Sign(acc.ask, timacrypto.DeviceCertBytes(encOf(phoneDev), sigOf(phoneDev)))),
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/users/me/signing-keys", phoneDev.token, bad, nil); code != http.StatusForbidden {
		t.Fatalf("чужая фраза: ожидался 403, получен %d", code)
	}
	good := map[string]any{
		"ask_pub":         b64.EncodeToString(acc.askPub()),
		"ask_sig":         b64.EncodeToString(ed25519.Sign(acc.identity, timacrypto.AskCertBytes(acc.askPub()))),
		"device_cert_sig": b64.EncodeToString(ed25519.Sign(acc.ask, timacrypto.DeviceCertBytes(encOf(phoneDev), sigOf(phoneDev)))),
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/users/me/signing-keys", phoneDev.token, good, nil); code != 200 {
		t.Fatalf("КПУ телефона: ожидался 200, получен %d", code)
	}
	// Телефон заверяет ПК своим КПУ; неверная подпись — отказ.
	path := "/api/v1/devices/" + pcDev.id + "/certificate"
	junk := map[string]any{"by": "ask", "sig": b64.EncodeToString(ed25519.Sign(wrong.ask, timacrypto.DeviceCertBytes(encOf(pcDev), sigOf(pcDev))))}
	if code := authedJSON(t, ts, "PUT", path, phoneDev.token, junk, nil); code != http.StatusForbidden {
		t.Fatalf("чужая подпись: ожидался 403, получен %d", code)
	}
	ok := map[string]any{"by": "ask", "sig": b64.EncodeToString(ed25519.Sign(acc.ask, timacrypto.DeviceCertBytes(encOf(pcDev), sigOf(pcDev))))}
	if code := authedJSON(t, ts, "PUT", path, phoneDev.token, ok, nil); code != http.StatusNoContent {
		t.Fatalf("заверение ПК: ожидался 204, получен %d", code)
	}
	// ПК КПУ не держит: заверять своим КПУ ему нечем.
	if code := authedJSON(t, ts, "PUT", "/api/v1/devices/"+phoneDev.id+"/certificate", pcDev.token, ok, nil); code != http.StatusForbidden {
		t.Fatalf("ПК без КПУ: ожидался 403, получен %d", code)
	}
	v := keysOf(t, ts, phoneDev, phoneDev.userID)
	certified := 0
	for _, d := range v.Devices {
		if d.CertBy == "ask" {
			certified++
		}
	}
	if certified != 2 {
		t.Fatalf("оба устройства должны быть заверены КПУ, заверено %d: %+v", certified, v.Devices)
	}
	// Отзыв телефона отзывает его КПУ — заверенное им у собеседников перестаёт быть доверенным.
	if code := authedJSON(t, ts, "DELETE", "/api/v1/devices/"+phoneDev.id, pcDev.token, nil, nil); code != http.StatusNoContent && code != 200 {
		t.Fatalf("отзыв телефона: %d", code)
	}
	v = keysOf(t, ts, pcDev, pcDev.userID)
	if len(v.SigningKeys) != 0 {
		t.Fatalf("КПУ отозванного телефона остался действующим: %+v", v.SigningKeys)
	}
}

// Привязка по QR: в строгом режиме телефон без КПУ новое устройство не приводит; телефон с КПУ
// заверяет его — и собеседники видят свидетельство.
func TestLinkConfirmCarriesCertificate(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRequire
	acc := newTrustAccount()
	phone, code, why := registerProof(t, ts, "+79990000106", acc.identityPub(), acc.withAsk)
	if code != 201 {
		t.Fatalf("телефон: %d %s", code, why)
	}
	start, encPub, signPub := startLink(t, ts, "Ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")
	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, phone.signKey); code != http.StatusForbidden {
		t.Fatalf("подтверждение без свидетельства в require: ожидался 403, получен %d", code)
	}
	b64 := base64.RawURLEncoding
	linkSig := ed25519.Sign(phone.signKey, linkSigningBytes(start.SessionID, secret, encPub[:], signPub))
	certSig := ed25519.Sign(acc.ask, timacrypto.DeviceCertBytes(encPub[:], signPub))
	if code := jsonAuth(t, ts, "POST", "/api/v1/link/confirm", phone.token, map[string]string{
		"session_id":      start.SessionID,
		"secret":          secret,
		"signature":       b64.EncodeToString(linkSig),
		"device_cert_sig": b64.EncodeToString(certSig),
	}, nil); code != 200 {
		t.Fatalf("подтверждение со свидетельством: ожидался 200, получен %d", code)
	}
	devices, err := srv.Store.ListDevices(t.Context(), phone.userID)
	if err != nil {
		t.Fatal(err)
	}
	for _, d := range devices {
		if d.DeviceID != phone.id && d.CertBy != "ask" {
			t.Fatalf("привязанное устройство без свидетельства: %+v", d)
		}
	}
	if len(devices) != 2 {
		t.Fatalf("ожидалось два устройства, есть %d", len(devices))
	}
}
