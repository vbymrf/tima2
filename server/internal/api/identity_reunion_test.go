package api

// Воссоединение личности по секретной фразе (Р4, хвост) — ДОКУМЕНТАЦИЯ/02 §5.
// Фраза даёт тот же Ed25519-ключ личности, что и раньше; здесь он представлен
// напрямую как ed25519-пара — генерация из слов уже проверена в messenger-crypto,
// сервер получает только сырой ключ.

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"tima/server/internal/store"
)

// registerRaw — регистрация с полным контролем над identity_pub/force_new_identity,
// для сценариев конфликта, которые registerDevice (без этих полей) не покрывает.
func registerRaw(t *testing.T, ts *httptest.Server, phone string, identityPub []byte, forceNew bool) (*device, int, string) {
	t.Helper()
	d, code, raw := registerReply(t, ts, phone, identityPub, forceNew)
	var errBody struct {
		Code string `json:"code"`
	}
	_ = json.Unmarshal(raw, &errBody)
	return d, code, errBody.Code
}

// registerReply — то же, но с ответом сервера целиком: в отказе бывают поля сверх кода
// (`start_anew` у identity_mismatch, ДУ10).
func registerReply(t *testing.T, ts *httptest.Server, phone string, identityPub []byte, forceNew bool) (*device, int, json.RawMessage) {
	t.Helper()
	d := &device{}
	seed := make([]byte, 32)
	if _, err := rand.Read(seed); err != nil {
		t.Fatal(err)
	}
	d.signKey = ed25519.NewKeyFromSeed(seed)
	encPub := make([]byte, 32)
	if _, err := rand.Read(encPub); err != nil {
		t.Fatal(err)
	}
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
	body := map[string]any{
		"registration_token": verifyResp.RegistrationToken,
		"encryption_pub":     b64.EncodeToString(d.encPub[:]),
		"signing_pub":        b64.EncodeToString(d.signKey.Public().(ed25519.PublicKey)),
		"force_new_identity": forceNew,
		"platform":           "android",
	}
	if len(identityPub) > 0 {
		body["identity_pub"] = b64.EncodeToString(identityPub)
	}
	var regResp struct {
		UserID      string `json:"user_id"`
		DeviceID    string `json:"device_id"`
		AccessToken string `json:"access_token"`
	}
	var raw json.RawMessage
	code := postJSON(t, ts, "/api/v1/auth/register", body, &raw)
	_ = json.Unmarshal(raw, &regResp)
	d.userID, d.id, d.token = regResp.UserID, regResp.DeviceID, regResp.AccessToken
	return d, code, raw
}

// Без реального конфликта force_new_identity не должен ничего форкать: иначе
// человек лишился бы своей истории без всякой причины.
func TestForceNewIdentityOnlyOnRealConflict(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	phone := "+79990060001"

	oldPub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	first, code, _ := registerRaw(t, ts, phone, oldPub, false)
	if code != 201 {
		t.Fatalf("первая регистрация: %d", code)
	}

	// Второе устройство: тот же ключ, force_new_identity=true — конфликта нет,
	// форка быть не должно.
	second, code, _ := registerRaw(t, ts, phone, oldPub, true)
	if code != 201 {
		t.Fatalf("совпадающий ключ: %d", code)
	}
	if second.userID != first.userID {
		t.Fatal("force_new_identity форкнул аккаунт при отсутствии конфликта")
	}

	// Третье устройство: чужой ключ без force_new_identity — обычный отказ.
	otherPub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	_, code, errCode := registerRaw(t, ts, phone, otherPub, false)
	if code != http.StatusForbidden || errCode != "identity_mismatch" {
		t.Fatalf("несовпадающий ключ без force: код %d/%q, ожидали 403/identity_mismatch", code, errCode)
	}

	// То же самое, но с force_new_identity=true — теперь форк оправдан.
	third, code, _ := registerRaw(t, ts, phone, otherPub, true)
	if code != 201 {
		t.Fatalf("force_new_identity при реальном конфликте: %d", code)
	}
	if third.userID == first.userID {
		t.Fatal("force_new_identity не форкнул при реальном конфликте")
	}
	person, err := srv.Store.PersonOfUser(ctx, third.userID)
	if err != nil {
		t.Fatal(err)
	}
	firstPerson, err := srv.Store.PersonOfUser(ctx, first.userID)
	if err != nil {
		t.Fatal(err)
	}
	if person != firstPerson {
		t.Fatal("форк должен остаться в том же аккаунте (тот же номер), а не завести новый")
	}
	ids := identitiesOf(t, ts, third.token, []string{third.userID})
	if ids[third.userID].Link != store.LinkAdministrative {
		t.Fatalf("«начать заново»: link = %q, ожидали %q (без подписи)", ids[third.userID].Link, store.LinkAdministrative)
	}
}

// reidentifyChallenge/reidentify дёргают напрямую — сквозной путь воссоединения.
func challengeFor(t *testing.T, ts *httptest.Server, bearer string) string {
	t.Helper()
	var resp struct {
		ChallengeToken string `json:"challenge_token"`
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/reidentify/challenge", bearer, nil, &resp); code != 200 {
		t.Fatalf("reidentify/challenge: %d", code)
	}
	if resp.ChallengeToken == "" {
		t.Fatal("пустой challenge_token")
	}
	return resp.ChallengeToken
}

// Р38 (2026-10-05): прежняя личность по фразе не возвращается — ни ручкой воссоединения
// (раньше она заводила ТРЕТЬЮ личность с ключом прежней), ни входом на новом устройстве.
// Отказ называет причину: это фраза прежней личности, а не «неверная фраза».
func TestReidentifyRefusesClosedIdentity(t *testing.T) {
	ts, srv := setup(t)
	ctx := context.Background()
	phone := "+79990060002"

	oldPub, oldPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	original, code, _ := registerRaw(t, ts, phone, oldPub, false)
	if code != 201 {
		t.Fatalf("исходная регистрация: %d", code)
	}

	// Устройство потеряно, фразы под рукой нет — «начать заново». Клиент при этом
	// заводит новую фразу и присылает её ключ (AuthStore.startAnew).
	newPub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	interim, code, _ := registerRaw(t, ts, phone, newPub, true)
	if code != 201 {
		t.Fatalf("force_new_identity: %d", code)
	}
	if interim.userID == original.userID {
		t.Fatal("«начать заново» обязан форкнуть личность")
	}

	// Фраза нашлась — воссоединение с устройства interim отклоняется.
	challenge := challengeFor(t, ts, interim.token)
	sig := ed25519.Sign(oldPriv, []byte(challenge))
	b64 := base64.RawURLEncoding
	var refusal struct {
		Code string `json:"code"`
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/reidentify", interim.token, map[string]string{
		"challenge_token": challenge,
		"identity_pub":    b64.EncodeToString(oldPub),
		"signature":       b64.EncodeToString(sig),
	}, &refusal); code != 409 || refusal.Code != "identity_closed" {
		t.Fatalf("reidentify: %d %q, ждали 409 identity_closed", code, refusal.Code)
	}

	// Вход фразой прежней личности на новом устройстве — тот же отказ.
	if _, code, body := registerRaw(t, ts, phone, oldPub, false); code != 403 || !strings.Contains(body, "identity_closed") {
		t.Fatalf("вход фразой прежней личности: %d %s, ждали 403 identity_closed", code, body)
	}

	// Третьей личности нет: текущая — interim, устройство на месте.
	ids := identitiesOf(t, ts, interim.token, []string{interim.userID, original.userID})
	if !ids[interim.userID].Current {
		t.Fatal("после отказа текущей обязана остаться новая личность")
	}
	devices, err := srv.Store.ListDevices(ctx, interim.userID)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, dv := range devices {
		if dv.DeviceID == interim.id {
			found = true
		}
	}
	if !found {
		t.Fatal("устройство не обязано никуда переезжать")
	}
}

// Челлендж, выданный другой сессии (другому текущему user_id), не принимается —
// иначе один и тот же челлендж годился бы где угодно.
func TestReidentifyRejectsForeignChallenge(t *testing.T) {
	ts, _ := setup(t)
	a := registerDevice(t, ts, "+79990060003")
	b := registerDevice(t, ts, "+79990060004")

	challenge := challengeFor(t, ts, a.token) // выдан для a
	oldPub, oldPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	sig := ed25519.Sign(oldPriv, []byte(challenge))
	b64 := base64.RawURLEncoding
	// Предъявляет устройство b — challenge_token.Subject != b.userID.
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/reidentify", b.token, map[string]string{
		"challenge_token": challenge,
		"identity_pub":    b64.EncodeToString(oldPub),
		"signature":       b64.EncodeToString(sig),
	}, nil); code != http.StatusForbidden {
		t.Fatalf("чужой challenge: %d, ожидали 403", code)
	}
}

// Неверная подпись (не тем ключом) отвергается — иначе identity_pub можно было бы
// просто заявить, не владея соответствующим приватным ключом.
func TestReidentifyRejectsBadSignature(t *testing.T) {
	ts, _ := setup(t)
	d := registerDevice(t, ts, "+79990060005")
	challenge := challengeFor(t, ts, d.token)

	claimedPub, _, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	_, otherPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	sig := ed25519.Sign(otherPriv, []byte(challenge)) // подписано НЕ claimedPub
	b64 := base64.RawURLEncoding
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/reidentify", d.token, map[string]string{
		"challenge_token": challenge,
		"identity_pub":    b64.EncodeToString(claimedPub),
		"signature":       b64.EncodeToString(sig),
	}, nil); code != http.StatusForbidden {
		t.Fatalf("подпись чужим ключом: %d, ожидали 403", code)
	}
}

// Ключ, который никогда не принадлежал ни одной личности этого аккаунта, —
// валидная подпись, но искать нечего.
func TestReidentifyRejectsUnrelatedKey(t *testing.T) {
	ts, _ := setup(t)
	d := registerDevice(t, ts, "+79990060006")
	challenge := challengeFor(t, ts, d.token)

	unrelatedPub, unrelatedPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	sig := ed25519.Sign(unrelatedPriv, []byte(challenge))
	b64 := base64.RawURLEncoding
	var errBody struct {
		Code string `json:"code"`
	}
	var raw json.RawMessage
	code := jsonAuth(t, ts, "POST", "/api/v1/users/me/reidentify", d.token, map[string]string{
		"challenge_token": challenge,
		"identity_pub":    b64.EncodeToString(unrelatedPub),
		"signature":       b64.EncodeToString(sig),
	}, &raw)
	_ = json.Unmarshal(raw, &errBody)
	if code != http.StatusNotFound || errBody.Code != "not_found" {
		t.Fatalf("несвязанный ключ: код %d/%q, ожидали 404/not_found", code, errBody.Code)
	}
}
