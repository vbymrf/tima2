package api

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"testing"
)

// TestGroupKeyRecoverNeedsIdentitySignature — Р42: просьба о ключах группы подписывается
// ключом личности из фразы. Без фразы у аккаунта — отказ, без подписи — отказ, с подписью —
// запрос принят. Укравший SIM имеет токен устройства, но фразы у него нет.
func TestGroupKeyRecoverNeedsIdentitySignature(t *testing.T) {
	ts, _ := setup(t)
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	owner, code, _ := registerRaw(t, ts, "+79990061001", pub, false)
	if code != 201 {
		t.Fatalf("владелец: %d", code)
	}
	bare, code, _ := registerRaw(t, ts, "+79990061002", nil, false)
	if code != 201 {
		t.Fatalf("участник без фразы: %d", code)
	}
	group := createGroupWith(t, ts, owner, bare)
	path := "/api/v1/groups/" + group + "/keys/recover"

	var refusal struct {
		Code string `json:"code"`
	}
	if code := jsonAuth(t, ts, "POST", path, bare.token, map[string]string{}, &refusal); code != 403 || refusal.Code != "phrase_required" {
		t.Fatalf("без фразы: %d %q, ждали 403 phrase_required", code, refusal.Code)
	}
	if code := jsonAuth(t, ts, "POST", path, owner.token, map[string]string{}, &refusal); code != 403 || refusal.Code != "bad_identity_sig" {
		t.Fatalf("без подписи: %d %q, ждали 403 bad_identity_sig", code, refusal.Code)
	}
	sig := ed25519.Sign(priv, recoverCanonical(group, owner.id))
	if code := jsonAuth(t, ts, "POST", path, owner.token,
		map[string]string{"signature": base64.RawURLEncoding.EncodeToString(sig)}, nil); code != 200 {
		t.Fatalf("с подписью ключом личности: %d, ждали 200", code)
	}
}

// TestGroupKeyRecoverCertifiedNeedsNoPhrase — заверенное устройство просит ключи группы без
// подписи фразой: заверить его без фразы было нельзя (заказчик 2026-10-06). Незаверенное —
// по-прежнему только с подписью, в любом режиме: не всякое устройство можно заверить.
func TestGroupKeyRecoverCertifiedNeedsNoPhrase(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRecord
	owner := newTrustAccount()
	phone, code, why := registerProof(t, ts, "+79990061021", owner.identityPub(), owner.withAsk)
	if code != 201 {
		t.Fatalf("телефон хозяина: %d %s", code, why)
	}
	other := registerDevice(t, ts, "+79990061022")
	group := createGroupWith(t, ts, other, phone)
	path := "/api/v1/groups/" + group + "/keys/recover"

	if code := jsonAuth(t, ts, "POST", path, phone.token, map[string]string{}, nil); code != 200 {
		t.Fatalf("заверенное без подписи: %d, ждали 200", code)
	}
	bare, code, _ := registerProof(t, ts, "+79990061021", owner.identityPub(), nil)
	if code != 201 {
		t.Fatalf("незаверенное в «записывать»: %d", code)
	}
	var refusal struct {
		Code string `json:"code"`
	}
	if code := jsonAuth(t, ts, "POST", path, bare.token, map[string]string{}, &refusal); code != 403 || refusal.Code != "bad_identity_sig" {
		t.Fatalf("незаверенное без подписи: %d %q, ждали 403 bad_identity_sig", code, refusal.Code)
	}
}

// TestGroupKeyHelpersAreModerators — ключ группы по просьбе отдают модератор, администратор и
// владелец, а рядовой участник — только своему же устройству (заказчик 2026-10-06, п. 1.7).
func TestGroupKeyHelpersAreModerators(t *testing.T) {
	ts, srv := setup(t)
	owner := registerDevice(t, ts, "+79990061011")
	moder := registerDevice(t, ts, "+79990061012")
	plain := registerDevice(t, ts, "+79990061013")
	late := registerDevice(t, ts, "+79990061014")
	group := createGroupAPI(t, ts, owner.token)
	addMemberAPI(t, ts, owner.token, group, moder.userID, "moderator")
	addMemberAPI(t, ts, owner.token, group, plain.userID, "member")
	if _, code := doRotate(t, ts, owner.token, group, 1, "periodic", []*device{owner, moder, plain}); code != 201 {
		t.Fatalf("ротация: %d", code)
	}
	addMemberAPI(t, ts, owner.token, group, late.userID, "member")

	helpers, err := srv.Store.HelperDevices(t.Context(), group, late.id, []int32{1})
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]bool{}
	for _, h := range helpers {
		got[h] = true
	}
	if !got[owner.id] || !got[moder.id] || got[plain.id] {
		t.Fatalf("помощники: владелец=%v модератор=%v рядовой=%v — ждали да, да, нет", got[owner.id], got[moder.id], got[plain.id])
	}

	b64 := base64.RawURLEncoding
	body := map[string]any{"requester_device": late.id, "keys": []map[string]any{{
		"gk_version": 1, "sender_ephemeral_pub": b64.EncodeToString(make([]byte, 32)),
		"wrapped": b64.EncodeToString(make([]byte, 24+16+32)),
	}}}
	path := "/api/v1/groups/" + group + "/keys/recover/provide"
	var refusal struct {
		Code string `json:"code"`
	}
	if code := jsonAuth(t, ts, "POST", path, plain.token, body, &refusal); code != 403 || refusal.Code != "not_moderator" {
		t.Fatalf("рядовой отдаёт ключ: %d %q, ждали 403 not_moderator", code, refusal.Code)
	}
	if code := jsonAuth(t, ts, "POST", path, moder.token, body, nil); code != 200 && code != 201 {
		t.Fatalf("модератор отдаёт ключ: %d, ждали 2xx", code)
	}
}
