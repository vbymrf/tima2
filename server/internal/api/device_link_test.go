package api

// Привязка нового устройства по QR (key-lifecycle.md §2) — сквозной путь:
// новое устройство /link/start → уже авторизованное устройство сканирует QR
// и подтверждает /link/confirm → новое устройство забирает сессию /link/claim.

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	neturl "net/url"
	"testing"
)

type linkStartResp struct {
	SessionID  string `json:"session_id"`
	QRPayload  string `json:"qr_payload"`
	ClaimToken string `json:"claim_token"`
	ExpiresAt  string `json:"expires_at"`
}

// startLink — новое устройство просит QR-сессию; возвращает и его сгенерированные
// ключи (понадобятся, чтобы позже сверить, что claim выдал доступ именно им).
func startLink(t *testing.T, ts *httptest.Server, name string) (linkStartResp, [32]byte, ed25519.PublicKey) {
	t.Helper()
	var encPub [32]byte
	if _, err := rand.Read(encPub[:]); err != nil {
		t.Fatal(err)
	}
	signPub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	b64 := base64.RawURLEncoding
	var resp linkStartResp
	code := postJSON(t, ts, "/api/v1/link/start", map[string]string{
		"encryption_pub": b64.EncodeToString(encPub[:]),
		"signing_pub":    b64.EncodeToString(signPub),
		"device_name":    name,
	}, &resp)
	if code != 200 {
		t.Fatalf("link/start: %d", code)
	}
	if resp.SessionID == "" || resp.QRPayload == "" || resp.ClaimToken == "" {
		t.Fatalf("link/start: пустой ответ %+v", resp)
	}
	return resp, encPub, signPub
}

// confirmLink — подтверждающее устройство подписывает данные ИЗ session/secret,
// как сделал бы клиент, разобравший QR (messenger-crypto DeviceLinkSignature.kt).
func confirmLink(
	t *testing.T, ts *httptest.Server, bearer string,
	sessionID, secret string, encPub [32]byte, signPub ed25519.PublicKey, signerPriv ed25519.PrivateKey,
) int {
	t.Helper()
	sig := ed25519.Sign(signerPriv, linkSigningBytes(sessionID, secret, encPub[:], signPub))
	return jsonAuth(t, ts, "POST", "/api/v1/link/confirm", bearer, map[string]string{
		"session_id": sessionID,
		"secret":     secret,
		"signature":  base64.RawURLEncoding.EncodeToString(sig),
	}, nil)
}

func TestDeviceLinkHappyPath(t *testing.T) {
	ts, srv := setup(t)
	ctx := t.Context()
	phone := registerDevice(t, ts, "+79990070001")

	start, encPub, signPub := startLink(t, ts, "Ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")

	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, phone.signKey); code != 200 {
		t.Fatalf("link/confirm: %d", code)
	}

	var claimResp struct {
		UserID      string `json:"user_id"`
		DeviceID    string `json:"device_id"`
		AccessToken string `json:"access_token"`
	}
	if code := postJSON(t, ts, "/api/v1/link/claim", map[string]string{
		"session_id": start.SessionID, "claim_token": start.ClaimToken,
	}, &claimResp); code != 200 {
		t.Fatalf("link/claim: %d", code)
	}
	if claimResp.UserID != phone.userID {
		t.Fatalf("claim выдал доступ не тому аккаунту: %s, ожидали %s", claimResp.UserID, phone.userID)
	}
	if claimResp.AccessToken == "" || claimResp.DeviceID == "" {
		t.Fatal("claim вернул пустые токен/device_id")
	}

	devices, err := srv.Store.ListDevices(ctx, phone.userID)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, d := range devices {
		if d.DeviceID == claimResp.DeviceID && string(d.EncryptionPub) == string(encPub[:]) {
			found = true
		}
	}
	if !found {
		t.Fatal("новое устройство не появилось в devices с теми ключами, что были в QR")
	}

	// Повторный claim тем же токеном — уже использован.
	if code := postJSON(t, ts, "/api/v1/link/claim", map[string]string{
		"session_id": start.SessionID, "claim_token": start.ClaimToken,
	}, nil); code != http.StatusForbidden {
		t.Fatalf("повторный claim: %d, ожидали 403", code)
	}
}

// Claim до confirm — не готово, а не ошибка: новое устройство просто продолжает опрос.
func TestDeviceLinkClaimBeforeConfirm(t *testing.T) {
	ts, _ := setup(t)
	start, _, _ := startLink(t, ts, "Ноутбук")

	var errBody struct {
		Code string `json:"code"`
	}
	var raw json.RawMessage
	code := postJSON(t, ts, "/api/v1/link/claim", map[string]string{
		"session_id": start.SessionID, "claim_token": start.ClaimToken,
	}, &raw)
	_ = json.Unmarshal(raw, &errBody)
	if code != http.StatusForbidden || errBody.Code != "not_ready" {
		t.Fatalf("claim до confirm: код %d/%q, ожидали 403/not_ready", code, errBody.Code)
	}
}

// Подпись чужим ключом (не тем устройством, что авторизовано в Bearer) отвергается —
// иначе Bearer-токен любого устройства подтверждал бы чужой QR.
func TestDeviceLinkRejectsBadSignature(t *testing.T) {
	ts, _ := setup(t)
	phone := registerDevice(t, ts, "+79990070002")
	start, encPub, signPub := startLink(t, ts, "Ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")

	_, otherPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, otherPriv); code != http.StatusForbidden {
		t.Fatalf("подпись чужим ключом: %d, ожидали 403", code)
	}
}

// Неверный secret (например, QR подделан или неверно считан) отвергается тем же
// кодом, что и просроченная/несуществующая сессия — не даёт различить причину.
func TestDeviceLinkRejectsBadSecret(t *testing.T) {
	ts, _ := setup(t)
	phone := registerDevice(t, ts, "+79990070003")
	start, encPub, signPub := startLink(t, ts, "Ноутбук")

	if code := confirmLink(t, ts, phone.token, start.SessionID, "not-the-real-secret", encPub, signPub, phone.signKey); code != http.StatusForbidden {
		t.Fatalf("неверный secret: %d, ожидали 403", code)
	}
}

// Повторный confirm той же сессии — уже подтверждена, второе устройство не появляется.
func TestDeviceLinkRejectsDoubleConfirm(t *testing.T) {
	ts, srv := setup(t)
	ctx := t.Context()
	phone := registerDevice(t, ts, "+79990070004")
	start, encPub, signPub := startLink(t, ts, "Ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")

	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, phone.signKey); code != 200 {
		t.Fatalf("первый confirm: %d", code)
	}
	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, phone.signKey); code != http.StatusForbidden {
		t.Fatalf("повторный confirm: %d, ожидали 403", code)
	}
	devices, err := srv.Store.ListDevices(ctx, phone.userID)
	if err != nil {
		t.Fatal(err)
	}
	count := 0
	for _, d := range devices {
		if string(d.EncryptionPub) == string(encPub[:]) {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("повторный confirm завёл устройство ещё раз: найдено %d, ожидали 1", count)
	}
}

// Подтверждать вправе только телефон (key-lifecycle.md §2). Десктоп с валидной
// подписью и валидным QR всё равно получает отказ — правило про роль устройства,
// а не про корректность запроса.
func TestDeviceLinkRejectsNonPhoneConfirmer(t *testing.T) {
	ts, srv := setup(t)
	desktop := registerDevice(t, ts, "+79990100001")
	if err := srv.Store.SetDevicePlatform(t.Context(), desktop.id, "desktop"); err != nil {
		t.Fatal(err)
	}

	start, encPub, signPub := startLink(t, ts, "Второй ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")

	var raw json.RawMessage
	sig := ed25519.Sign(desktop.signKey, linkSigningBytes(start.SessionID, secret, encPub[:], signPub))
	code := jsonAuth(t, ts, "POST", "/api/v1/link/confirm", desktop.token, map[string]string{
		"session_id": start.SessionID,
		"secret":     secret,
		"signature":  base64.RawURLEncoding.EncodeToString(sig),
	}, &raw)
	var errBody struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(raw, &errBody)
	if code != http.StatusForbidden || errBody.Code != "not_a_phone" {
		t.Fatalf("подтверждение с десктопа: код %d/%q, ожидали 403/not_a_phone", code, errBody.Code)
	}
}

// Устройство, зарегистрированное до появления колонки platform, тоже не может
// подтверждать — и чинится самообъявлением, как это делает клиент при запуске.
func TestDeviceLinkUnknownPlatformHealsAfterDeclaration(t *testing.T) {
	ts, srv := setup(t)
	phone := registerDevice(t, ts, "+79990100002")
	if err := srv.Store.SetDevicePlatform(t.Context(), phone.id, ""); err != nil {
		t.Fatal(err)
	}

	start, encPub, signPub := startLink(t, ts, "Ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")
	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, phone.signKey); code != http.StatusForbidden {
		t.Fatalf("подтверждение без объявленной платформы: %d, ожидали 403", code)
	}

	// Клиент объявляет платформу — и та же привязка проходит.
	if code := jsonAuth(t, ts, "PUT", "/api/v1/devices/me/platform", phone.token,
		map[string]string{"platform": "android"}, nil); code != 200 {
		t.Fatalf("объявление платформы: %d", code)
	}
	if code := confirmLink(t, ts, phone.token, start.SessionID, secret, encPub, signPub, phone.signKey); code != 200 {
		t.Fatalf("подтверждение после объявления платформы: %d", code)
	}
}

// Подтвердившее устройство может сразу отдать новому ключи истории: новое
// устройство — устройство участника чата, поэтому /recover/provide его принимает.
// Это и есть механизм, которым QR-привязка возвращает переписку — сам новый
// клиент попросить её не может (нет ключа личности из фразы).
func TestDeviceLinkAllowsHistoryHandover(t *testing.T) {
	ts, srv := setup(t)
	ctx := t.Context()
	alice := registerDevice(t, ts, "+79990090001")
	bob := registerDevice(t, ts, "+79990090002")

	// Переписка, существующая ДО появления нового устройства: обёртки этого
	// сообщения адресованы только текущим устройствам, нового среди них нет.
	env := sealEnvelope(t, alice, []*device{alice, bob}, 7001, []byte("до привязки"))
	resp := post(t, ts, env, alice.token, "11111111-0000-0000-0000-000000009001")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("отправка сообщения: %d", resp.StatusCode)
	}

	start, encPub, signPub := startLink(t, ts, "Ноутбук")
	secret := qrParam(t, start.QRPayload, "secret")
	var confirm struct {
		DeviceID string `json:"device_id"`
	}
	sig := ed25519.Sign(alice.signKey, linkSigningBytes(start.SessionID, secret, encPub[:], signPub))
	if code := jsonAuth(t, ts, "POST", "/api/v1/link/confirm", alice.token, map[string]string{
		"session_id": start.SessionID,
		"secret":     secret,
		"signature":  base64.RawURLEncoding.EncodeToString(sig),
	}, &confirm); code != 200 {
		t.Fatalf("link/confirm: %d", code)
	}
	if confirm.DeviceID == "" {
		t.Fatal("link/confirm не вернул device_id — телефону некуда заворачивать историю")
	}

	// Новое устройство — устройство участника чата: проверка, на которой держится
	// выдача ключей (chatRecoverProvide → IsChatParticipantDevice).
	ok, err := srv.Store.IsChatParticipantDevice(ctx, chatID, confirm.DeviceID)
	if err != nil {
		t.Fatal(err)
	}
	if !ok {
		t.Fatal("подключённое устройство не признано устройством участника — историю ему не отдать")
	}
}

// qrParam — грубый разбор query-параметра из tima://link/v1?... для тестов;
// настоящий разбор на клиенте — messenger-crypto DeviceLinkSignature.kt.
func qrParam(t *testing.T, qrPayload, name string) string {
	t.Helper()
	u, err := neturl.Parse(qrPayload)
	if err != nil {
		t.Fatal(err)
	}
	v := u.Query().Get(name)
	if v == "" {
		t.Fatalf("qr_payload не содержит %q: %s", name, qrPayload)
	}
	return v
}
