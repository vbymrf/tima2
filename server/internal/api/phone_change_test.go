package api

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"tima/server/internal/store"
)

// Смена SIM — смена номера аккаунта (ДУ9; порядок — ответ заказчика 2026-10-06): заявка —
// фраза и код на прежний номер; в окне — фраза той же личности и код на новый номер.

type phoneChangeReply struct {
	Active   bool   `json:"active"`
	Mine     bool   `json:"mine"`
	NewPhone string `json:"new_phone"`
	Outcome  string `json:"outcome"`
}

func openPhoneWindow(t *testing.T, db *pgx.Conn, past bool) {
	t.Helper()
	q := `UPDATE phone_changes SET window_from = now() - interval '1 minute', window_to = now() + interval '1 minute' WHERE closed_at IS NULL`
	if past {
		q = `UPDATE phone_changes SET window_from = now() - interval '2 minutes', window_to = now() - interval '1 second' WHERE closed_at IS NULL`
	}
	if _, err := db.Exec(context.Background(), q); err != nil {
		t.Fatal(err)
	}
}

func TestPhoneChange(t *testing.T) {
	ts, srv := setup(t)
	db := testDB(t)
	ctx := context.Background()
	oldNum, newNum, takenNum := "+79990069001", "+79990069002", "+79990069003"
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	dev, code, _ := registerRaw(t, ts, oldNum, pub, false)
	if code != 201 {
		t.Fatalf("регистрация: %d", code)
	}
	otherPub, _, _ := ed25519.GenerateKey(rand.Reader)
	if _, code, _ := registerRaw(t, ts, takenNum, otherPub, false); code != 201 {
		t.Fatalf("второй аккаунт: %d", code)
	}
	start := func(smsPhone, target string, key ed25519.PrivateKey) (int, string) {
		body := phraseBody(t, ts, smsPhone, dev.token, key, nil)
		body["new_phone"] = target
		var reply struct {
			Code string `json:"code"`
		}
		c := jsonAuth(t, ts, "POST", "/api/v1/users/me/phone-change", dev.token, body, &reply)
		return c, reply.Code
	}

	// Заявка: код — на прежний номер, фраза — своей личности, номер — свободный.
	if c, e := start(newNum, newNum, priv); c != 403 || e != "phone_mismatch" {
		t.Fatalf("код на новый номер при заявке: %d %s", c, e)
	}
	_, wrong, _ := ed25519.GenerateKey(rand.Reader)
	if c, e := start(oldNum, newNum, wrong); c != 403 || e != "bad_signature" {
		t.Fatalf("чужая фраза: %d %s", c, e)
	}
	if c, e := start(oldNum, takenNum, priv); c != 409 || e != "phone_taken" {
		t.Fatalf("занятый номер: %d %s", c, e)
	}
	if c, e := start(oldNum, newNum, priv); c != 200 {
		t.Fatalf("заявка: %d %s", c, e)
	}
	if c, e := start(oldNum, newNum, priv); c != 409 || e != "phone_change_open" {
		t.Fatalf("вторая заявка: %d %s", c, e)
	}
	var st phoneChangeReply
	if c := getAuthed(t, ts, dev.token, "/api/v1/users/me/phone-change", &st); c != 200 || !st.Active || !st.Mine || st.NewPhone != "+7 ••• •• 02" {
		t.Fatalf("состояние: %d %+v", c, st)
	}

	confirm := func(smsPhone string, key ed25519.PrivateKey) (int, string) {
		var reply struct {
			Code string `json:"code"`
		}
		c := jsonAuth(t, ts, "POST", "/api/v1/users/me/phone-change/confirm", dev.token, phraseBody(t, ts, smsPhone, dev.token, key, nil), &reply)
		return c, reply.Code
	}
	// До окна — нет.
	if c, e := confirm(newNum, priv); c != 409 || e != "not_in_window" {
		t.Fatalf("подтверждение до окна: %d %s", c, e)
	}
	openPhoneWindow(t, db, false)
	stepPhoneChanges(ctx, srv.Store, srv.notifier(), time.Now())
	// В окне прежний номер уже не нужен — код только на новый; фраза — та же.
	if c, e := confirm(oldNum, priv); c != 403 || e != "phone_mismatch" {
		t.Fatalf("код на прежний номер при подтверждении: %d %s", c, e)
	}
	if c, e := confirm(newNum, wrong); c != 403 || e != "bad_signature" {
		t.Fatalf("чужая фраза при подтверждении: %d %s", c, e)
	}
	if c, e := confirm(newNum, priv); c != 200 {
		t.Fatalf("подтверждение: %d %s", c, e)
	}
	if who, err := srv.Store.FindUserByPhone(ctx, newNum); err != nil || who != dev.userID {
		t.Fatalf("новый номер не у аккаунта: %q %v", who, err)
	}
	if _, err := srv.Store.FindUserByPhone(ctx, oldNum); !errors.Is(err, store.ErrUserUnknown) {
		t.Fatalf("прежний номер не освободился: %v", err)
	}
	if c := getAuthed(t, ts, dev.token, "/api/v1/users/me/phone-change", &st); c != 200 || st.Active {
		t.Fatalf("после смены заявка открыта: %d %+v", c, st)
	}
}

// Перерегистрация отменяет заявку на смену номера, и до конца спора новую не подать; окно
// без подтверждения гасит заявку — номер прежний.
func TestPhoneChangeCancelledAndExpired(t *testing.T) {
	ts, srv := setup(t)
	db := testDB(t)
	ctx := context.Background()
	num, target := "+79990069011", "+79990069012"
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	dev, code, _ := registerRaw(t, ts, num, pub, false)
	if code != 201 {
		t.Fatalf("регистрация: %d", code)
	}
	start := func() int {
		body := phraseBody(t, ts, num, dev.token, priv, nil)
		body["new_phone"] = target
		return jsonAuth(t, ts, "POST", "/api/v1/users/me/phone-change", dev.token, body, nil)
	}
	// Телефон заверил себя своим ключом — так у всех телефонов по фразе; без этого
	// перерегистрация отключила бы его (Р50).
	certifyByOwnAsk(t, db, dev.userID, dev.id)
	if c := start(); c != 200 {
		t.Fatalf("заявка: %d", c)
	}
	// Окно кончилось без подтверждения — заявка гаснет, номер прежний.
	openPhoneWindow(t, db, true)
	stepPhoneChanges(ctx, srv.Store, srv.notifier(), time.Now())
	var st phoneChangeReply
	if c := getAuthed(t, ts, dev.token, "/api/v1/users/me/phone-change", &st); c != 200 || st.Active {
		t.Fatalf("после окна заявка открыта: %d %+v", c, st)
	}
	if who, err := srv.Store.FindUserByPhone(ctx, num); err != nil || who != dev.userID {
		t.Fatalf("номер сменился без подтверждения: %q %v", who, err)
	}

	// Новая заявка, затем перерегистрация — заявка отменена, новую не подать до конца спора.
	if c := start(); c != 200 {
		t.Fatalf("вторая заявка: %d", c)
	}
	newPub, _, _ := ed25519.GenerateKey(rand.Reader)
	if _, c, e := registerRereg(t, ts, num, newPub, dev.token, priv); c != 201 {
		t.Fatalf("перерегистрация: %d %s", c, e)
	}
	if c := getAuthed(t, ts, dev.token, "/api/v1/users/me/phone-change", &st); c != 200 || st.Active {
		t.Fatalf("перерегистрация не отменила заявку: %d %+v", c, st)
	}
	body := phraseBody(t, ts, num, dev.token, priv, nil)
	body["new_phone"] = target
	var reply struct {
		Code string `json:"code"`
	}
	if c := jsonAuth(t, ts, "POST", "/api/v1/users/me/phone-change", dev.token, body, &reply); c != 409 || reply.Code != "rereg_open" {
		t.Fatalf("заявка во время спора: %d %s", c, reply.Code)
	}
}
