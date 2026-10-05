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
