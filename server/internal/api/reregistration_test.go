package api

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

// Перерегистрация и удаление личности (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ9, ДУ11; Р34, Р37,
// Р39, Р50). Сроки двигаются прямо в базе: проход по срокам — тот же, что крутит сервер.

func testDB(t *testing.T) *pgx.Conn {
	t.Helper()
	url := os.Getenv("TIMA_TEST_DATABASE_URL")
	if url == "" {
		url = "postgres://tima:tima-dev-only@localhost:5432/tima_test"
	}
	conn, err := pgx.Connect(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { conn.Close(context.Background()) })
	return conn
}

// smsToken — код из SMS на номер (стенд отдаёт его в ответе).
func smsToken(t *testing.T, ts *httptest.Server, phone string) string {
	t.Helper()
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
	return verified.RegistrationToken
}

// registerRereg — перерегистрация: новая личность newPub, доказательство фразы С — подпись
// вызова её сессии ключом oldPriv.
func registerRereg(t *testing.T, ts *httptest.Server, phone string, newPub ed25519.PublicKey, oldSession string, oldPriv ed25519.PrivateKey) (*device, int, string) {
	t.Helper()
	d := &device{}
	_, d.signKey, _ = ed25519.GenerateKey(rand.Reader)
	_, _ = rand.Read(d.encPub[:])
	challenge := challengeFor(t, ts, oldSession)
	b64 := base64.RawURLEncoding
	body := map[string]any{
		"registration_token": smsToken(t, ts, phone),
		"encryption_pub":     b64.EncodeToString(d.encPub[:]),
		"signing_pub":        b64.EncodeToString(d.signKey.Public().(ed25519.PublicKey)),
		"identity_pub":       b64.EncodeToString(newPub),
		"force_new_identity": true,
		"platform":           "android",
		"reregister":         map[string]string{"challenge_token": challenge, "signature": b64.EncodeToString(ed25519.Sign(oldPriv, []byte(challenge)))},
	}
	var raw json.RawMessage
	code := postJSON(t, ts, "/api/v1/auth/register", body, &raw)
	var reg struct {
		UserID, DeviceID, AccessToken, Code string
	}
	var m map[string]string
	_ = json.Unmarshal(raw, &m)
	reg.UserID, reg.DeviceID, reg.AccessToken, reg.Code = m["user_id"], m["device_id"], m["access_token"], m["code"]
	d.userID, d.id, d.token = reg.UserID, reg.DeviceID, reg.AccessToken
	return d, code, reg.Code
}

// phraseBody — тело заявки и подтверждения: SMS и подпись вызова ключами личностей.
func phraseBody(t *testing.T, ts *httptest.Server, phone, session string, priv ed25519.PrivateKey, oldPriv ed25519.PrivateKey) map[string]string {
	t.Helper()
	challenge := challengeFor(t, ts, session)
	b64 := base64.RawURLEncoding
	body := map[string]string{
		"registration_token": smsToken(t, ts, phone),
		"challenge_token":    challenge,
		"signature":          b64.EncodeToString(ed25519.Sign(priv, []byte(challenge))),
	}
	if oldPriv != nil {
		body["old_signature"] = b64.EncodeToString(ed25519.Sign(oldPriv, []byte(challenge)))
	}
	return body
}

type reregReply struct {
	Active    bool      `json:"active"`
	Role      string    `json:"role"`
	Round     int       `json:"round"`
	Disputed  bool      `json:"disputed"`
	Confirmed bool      `json:"confirmed"`
	WindowTo  time.Time `json:"window_to"`
}

// revokedReason — отказ отключённому устройству: код, причина и срок удаления личности.
func revokedReason(t *testing.T, ts *httptest.Server, token string) (int, string, string) {
	t.Helper()
	req, _ := http.NewRequest("GET", ts.URL+"/api/v1/users/me/rereg", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	var body struct {
		Reason   string `json:"reason"`
		DeleteAt string `json:"delete_at"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&body)
	return resp.StatusCode, body.Reason, body.DeleteAt
}

func certifyByIdentity(t *testing.T, db *pgx.Conn, deviceID string) {
	t.Helper()
	if _, err := db.Exec(context.Background(), `UPDATE devices SET cert_by = 'identity' WHERE device_id = $1`, deviceID); err != nil {
		t.Fatal(err)
	}
}

// certifyByOwnAsk — телефон, вошедший по фразе: заверен своим же ключом подписи устройств.
func certifyByOwnAsk(t *testing.T, db *pgx.Conn, userID, deviceID string) {
	t.Helper()
	ctx := context.Background()
	var askID string
	if err := db.QueryRow(ctx, `INSERT INTO account_signing_keys (user_id, device_id, ask_pub, ask_sig)
		VALUES ($1, $2, decode('00', 'hex'), decode('00', 'hex')) RETURNING ask_id`, userID, deviceID).Scan(&askID); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(ctx, `UPDATE devices SET cert_by = 'ask', cert_ask_id = $2 WHERE device_id = $1`, deviceID, askID); err != nil {
		t.Fatal(err)
	}
}

// openWindow — сдвинуть окно открытого процесса на «сейчас»; closeWindow — в прошлое.
func openWindow(t *testing.T, db *pgx.Conn) {
	t.Helper()
	if _, err := db.Exec(context.Background(), `UPDATE reregistrations SET window_from = now() - interval '1 minute',
		window_to = now() + interval '1 minute' WHERE closed_at IS NULL`); err != nil {
		t.Fatal(err)
	}
}

func closeWindow(t *testing.T, db *pgx.Conn) {
	t.Helper()
	if _, err := db.Exec(context.Background(), `UPDATE reregistrations SET window_from = now() - interval '2 minutes',
		window_to = now() - interval '1 second' WHERE closed_at IS NULL`); err != nil {
		t.Fatal(err)
	}
}

// TestReregistrationDispute — спор: запуск, встречная заявка, оба подтвердили (продление), затем
// подтвердила только С — Н удаляется, С снова текущая (Р34, Р37, Р39, Р50).
func TestReregistrationDispute(t *testing.T) {
	ts, srv := setup(t)
	db := testDB(t)
	ctx := context.Background()
	times := ReregTimes{Wait: time.Hour, Window: time.Hour, DeleteAfter: time.Hour}.orDefault()
	phone := "+79990067001"
	oldPub, oldPriv, _ := ed25519.GenerateKey(rand.Reader)
	oldPhone, code, _ := registerRaw(t, ts, phone, oldPub, false)
	if code != 201 {
		t.Fatalf("С: %d", code)
	}
	// Телефон С заверил себя своим ключом подписи устройств — так у всех телефонов по фразе.
	certifyByOwnAsk(t, db, oldPhone.userID, oldPhone.id)
	oldPC, code, _ := registerRaw(t, ts, phone, oldPub, false) // заверено не ключом личности
	if code != 201 {
		t.Fatalf("ПК С: %d", code)
	}
	// Запрет «Начать заново» перерегистрацию не касается (Р36).
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/start-anew-ban", oldPhone.token, phraseBody(t, ts, phone, oldPhone.token, oldPriv, nil), nil); code != 200 {
		t.Fatalf("запрет: %d", code)
	}

	newPub, newPriv, _ := ed25519.GenerateKey(rand.Reader)
	_, wrong, _ := ed25519.GenerateKey(rand.Reader)
	if _, code, errCode := registerRereg(t, ts, phone, newPub, oldPhone.token, wrong); code != 403 || errCode != "bad_signature" {
		t.Fatalf("перерегистрация чужой фразой: %d %s", code, errCode)
	}
	newPhone, code, errCode := registerRereg(t, ts, phone, newPub, oldPhone.token, oldPriv)
	if code != 201 {
		t.Fatalf("перерегистрация: %d %s", code, errCode)
	}
	certifyByOwnAsk(t, db, newPhone.userID, newPhone.id)
	if newPhone.userID == oldPhone.userID {
		t.Fatal("перерегистрация обязана завести новую личность")
	}

	var st reregReply
	if code := getAuthed(t, ts, newPhone.token, "/api/v1/users/me/rereg", &st); code != 200 || !st.Active || st.Role != "new" {
		t.Fatalf("состояние Н: %d %+v", code, st)
	}
	// Р50: телефон С с ключом личности живёт; ПК С — отключён с причиной.
	if code := getAuthed(t, ts, oldPhone.token, "/api/v1/users/me/rereg", &st); code != 200 || st.Role != "old" || st.Disputed {
		t.Fatalf("состояние С: %d %+v", code, st)
	}
	if code, reason, _ := revokedReason(t, ts, oldPC.token); code != 401 || reason != "reregistered" {
		t.Fatalf("ПК С: %d %q, ждали 401 reregistered", code, reason)
	}
	// Вторая новая личность и отмена фразой С поверх процесса — отказ.
	otherPub, _, _ := ed25519.GenerateKey(rand.Reader)
	if _, code, errCode := registerRaw(t, ts, phone, otherPub, true); code != 409 || errCode != "rereg_open" {
		t.Fatalf("«Начать заново» во время перерегистрации: %d %s", code, errCode)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/identity/cancel", oldPhone.token, phraseBody(t, ts, phone, oldPhone.token, oldPriv, nil), nil); code != 409 {
		t.Fatalf("отмена фразой С во время перерегистрации: %d, ждали 409", code)
	}

	// Встречная заявка — только от С, фраза С и SMS.
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/claim", newPhone.token, phraseBody(t, ts, phone, newPhone.token, newPriv, nil), nil); code != 409 {
		t.Fatalf("заявка от Н: %d, ждали 409", code)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/claim", oldPhone.token, phraseBody(t, ts, phone, oldPhone.token, oldPriv, nil), &st); code != 200 || !st.Disputed {
		t.Fatalf("заявка С: %d %+v", code, st)
	}
	// Спор: заверять нельзя никому.
	if code := jsonAuth(t, ts, "PUT", "/api/v1/devices/"+newPhone.id+"/certificate", newPhone.token, map[string]string{"by": "identity", "sig": "AA"}, nil); code != 409 {
		t.Fatalf("заверение во время спора: %d, ждали 409", code)
	}
	// Вне окна подтверждения не принимаются.
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/confirm", newPhone.token, phraseBody(t, ts, phone, newPhone.token, newPriv, oldPriv), nil); code != 409 {
		t.Fatalf("подтверждение до окна: %d, ждали 409", code)
	}

	// Окно: Н — без фразы С не подтверждает; с обеими — да. С — тоже.
	openWindow(t, db)
	stepRereg(ctx, srv.Store, srv.notifier(), times, time.Now())
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/confirm", newPhone.token, phraseBody(t, ts, phone, newPhone.token, newPriv, wrong), nil); code != 403 {
		t.Fatalf("подтверждение Н без прежней фразы: %d, ждали 403", code)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/confirm", newPhone.token, phraseBody(t, ts, phone, newPhone.token, newPriv, oldPriv), &st); code != 200 || !st.Confirmed {
		t.Fatalf("подтверждение Н: %d %+v", code, st)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/confirm", oldPhone.token, phraseBody(t, ts, phone, oldPhone.token, oldPriv, nil), nil); code != 200 {
		t.Fatalf("подтверждение С: %d", code)
	}
	// Подтвердили оба — продление без предела: новое окно, круг 1.
	closeWindow(t, db)
	stepRereg(ctx, srv.Store, srv.notifier(), times, time.Now())
	if code := getAuthed(t, ts, newPhone.token, "/api/v1/users/me/rereg", &st); code != 200 || !st.Active || st.Round != 1 || st.Confirmed || !st.WindowTo.After(time.Now()) {
		t.Fatalf("после подтверждения обеих: %d %+v", code, st)
	}

	// Второй круг: подтвердила только С — Н удаляется, С снова текущая.
	openWindow(t, db)
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/confirm", oldPhone.token, phraseBody(t, ts, phone, oldPhone.token, oldPriv, nil), nil); code != 200 {
		t.Fatalf("подтверждение С, второй круг: %d", code)
	}
	closeWindow(t, db)
	stepRereg(ctx, srv.Store, srv.notifier(), times, time.Now())
	if code, reason, deleteAt := revokedReason(t, ts, newPhone.token); code != 401 || reason != "rereg_not_confirmed" || deleteAt == "" {
		t.Fatalf("телефон Н после исхода: %d %q %q", code, reason, deleteAt)
	}
	if code := getAuthed(t, ts, oldPhone.token, "/api/v1/users/me/rereg", &st); code != 200 || st.Active {
		t.Fatalf("у С процесса больше нет: %d %+v", code, st)
	}
	ids, err := srv.Store.IdentitiesOf(ctx, []string{oldPhone.userID, newPhone.userID})
	if err != nil {
		t.Fatal(err)
	}
	if !ids[oldPhone.userID].Current || ids[newPhone.userID].Current || ids[newPhone.userID].DeleteAt == nil || !ids[newPhone.userID].Reregistered {
		t.Fatalf("справочник после исхода: С %+v, Н %+v", ids[oldPhone.userID], ids[newPhone.userID])
	}
	// Вход фразой С снова пускает: она текущая.
	if _, code, _ := registerRaw(t, ts, phone, oldPub, false); code != 201 {
		t.Fatalf("вход фразой С после исхода: %d", code)
	}

	// ДУ11: срок вышел — личность удалена.
	if _, err := db.Exec(ctx, `UPDATE users SET delete_at = now() - interval '1 second' WHERE user_id = $1`, newPhone.userID); err != nil {
		t.Fatal(err)
	}
	stepRereg(ctx, srv.Store, srv.notifier(), times, time.Now())
	ids, _ = srv.Store.IdentitiesOf(ctx, []string{newPhone.userID})
	if ids[newPhone.userID].DeletedAt == nil {
		t.Fatalf("Н после срока удаления: %+v", ids[newPhone.userID])
	}
}

// TestReregistrationNewWins — встречной заявки нет, Н подтвердила: удаляется С, её телефон с
// ключом личности отключается сразу (Р39), заверять Н может как обычно.
func TestReregistrationNewWins(t *testing.T) {
	ts, srv := setup(t)
	db := testDB(t)
	ctx := context.Background()
	times := ReregTimes{}.orDefault()
	phone := "+79990067002"
	oldPub, oldPriv, _ := ed25519.GenerateKey(rand.Reader)
	oldPhone, code, _ := registerRaw(t, ts, phone, oldPub, false)
	if code != 201 {
		t.Fatalf("С: %d", code)
	}
	certifyByIdentity(t, db, oldPhone.id)
	newPub, newPriv, _ := ed25519.GenerateKey(rand.Reader)
	newPhone, code, errCode := registerRereg(t, ts, phone, newPub, oldPhone.token, oldPriv)
	if code != 201 {
		t.Fatalf("перерегистрация: %d %s", code, errCode)
	}
	// С без встречной заявки подтверждать нечего.
	openWindow(t, db)
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/confirm", oldPhone.token, phraseBody(t, ts, phone, oldPhone.token, oldPriv, nil), nil); code != 409 {
		t.Fatalf("подтверждение С без заявки: %d, ждали 409", code)
	}
	if code := jsonAuth(t, ts, "POST", "/api/v1/users/me/rereg/confirm", newPhone.token, phraseBody(t, ts, phone, newPhone.token, newPriv, oldPriv), nil); code != 200 {
		t.Fatalf("подтверждение Н: %d", code)
	}
	closeWindow(t, db)
	stepRereg(ctx, srv.Store, srv.notifier(), times, time.Now())
	if code, reason, _ := revokedReason(t, ts, oldPhone.token); code != 401 || reason != "rereg_confirmed" {
		t.Fatalf("телефон С после исхода: %d %q", code, reason)
	}
	// Причину узнаёт и само устройство, чей токен уже истёк, — подписью при обновлении токена;
	// чужая подпись причины не получает.
	issued := time.Now().Unix()
	renew := func(key ed25519.PrivateKey) map[string]any {
		var out map[string]any
		postJSON(t, ts, "/api/v1/auth/device/token", map[string]any{
			"user_id": oldPhone.userID, "device_id": oldPhone.id, "issued_at": issued,
			"signature": base64.RawURLEncoding.EncodeToString(ed25519.Sign(key, deviceTokenSigningBytes(oldPhone.userID, oldPhone.id, issued))),
		}, &out)
		return out
	}
	if out := renew(oldPhone.signKey); out["reason"] != "rereg_confirmed" || out["delete_at"] == nil {
		t.Fatalf("обновление токена отключённым устройством: %v", out)
	}
	_, stranger, _ := ed25519.GenerateKey(rand.Reader)
	if out := renew(stranger); out["reason"] != nil {
		t.Fatalf("чужая подпись узнала причину: %v", out)
	}
	var st reregReply
	if code := getAuthed(t, ts, newPhone.token, "/api/v1/users/me/rereg", &st); code != 200 || st.Active {
		t.Fatalf("у Н процесса больше нет: %d %+v", code, st)
	}
	ids, _ := srv.Store.IdentitiesOf(ctx, []string{oldPhone.userID, newPhone.userID})
	if !ids[newPhone.userID].Current || ids[oldPhone.userID].DeleteAt == nil {
		t.Fatalf("справочник: С %+v, Н %+v", ids[oldPhone.userID], ids[newPhone.userID])
	}
	if strings.Contains(revokedText("rereg_confirmed"), "отозвано") {
		t.Fatal("у причины обязан быть свой текст")
	}
}
