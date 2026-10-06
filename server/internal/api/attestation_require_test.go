package api

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"net/http"
	"testing"

	"tima/server/internal/attest"
	timacrypto "tima/server/internal/crypto"
)

// Годная аттестация (ДУ8, Р25; ПЛАН-(ЗБ) ЗБ2): цепочка, вызов и подпись сошлись, ключ в
// защищённой части, загрузка проверена, приложение наше; корень и подпись APK — из списков; в
// «требовать» без списков годной не бывает.
func TestJudgeAttestation(t *testing.T) {
	good := attest.Result{ChainOK: true, ChallengeOK: true, SignatureOK: true, RootSPKI: "aa11",
		SecurityLevel: "tee", VerifiedBoot: "verified", PackageName: attestPackage, SignatureDigest: "bb22"}
	lists := AttestationPolicy{Roots: []string{"AA11"}, Apps: []string{"bb22"}}
	cases := []struct {
		name string
		res  attest.Result
		p    AttestationPolicy
		mode string
		ok   bool
	}{
		{"годная со списками", good, lists, trustRequire, true},
		{"«записывать» без списков", good, AttestationPolicy{}, trustRecord, true},
		{"«требовать» без списков", good, AttestationPolicy{}, trustRequire, false},
		{"корень не из списка", func() attest.Result { r := good; r.RootSPKI = "ff"; return r }(), lists, trustRecord, false},
		{"подпись APK не из списка", func() attest.Result { r := good; r.SignatureDigest = "ff"; return r }(), lists, trustRecord, false},
		{"ключ не в чипе", func() attest.Result { r := good; r.SecurityLevel = "software"; return r }(), lists, trustRequire, false},
		{"загрузка не проверена", func() attest.Result { r := good; r.VerifiedBoot = "unverified"; return r }(), lists, trustRequire, false},
		{"чужое приложение", func() attest.Result { r := good; r.PackageName = "x.y"; return r }(), lists, trustRequire, false},
		{"цепочка не сошлась", func() attest.Result { r := good; r.ChainOK = false; return r }(), lists, trustRequire, false},
	}
	for _, c := range cases {
		if ok, why := judgeAttestation(c.res, c.p, c.mode); ok != c.ok {
			t.Errorf("%s: получено %v (%s), ждали %v", c.name, ok, why, c.ok)
		}
	}
}

// «Требовать» (ДУ8, Р17): личность рождается только на телефоне Android, и этот телефон до годной
// аттестации стоит под требованием ЗБ1; КПУ выдаётся только аттестованному телефону.
func TestAttestationRequire(t *testing.T) {
	ts, srv := setup(t)
	srv.DeviceTrust = trustRecord
	srv.Attestation = trustRequire
	ctx := context.Background()

	// Новая личность с ПК — отказ.
	acc := newTrustAccount()
	desktop := func(enc, sig []byte) map[string]any { return map[string]any{"platform": "desktop"} }
	if _, code, errCode := registerProof(t, ts, "+79990000221", acc.identityPub(), desktop); code != http.StatusForbidden || errCode != "phone_required" {
		t.Fatalf("личность на ПК: %d %s", code, errCode)
	}
	// На телефоне — заводится, но до аттестации телефон под требованием.
	phone, code, _ := registerProof(t, ts, "+79990000222", acc.identityPub(), acc.withAsk)
	if code != http.StatusCreated {
		t.Fatalf("личность на телефоне: %d", code)
	}
	if code := getAuthed(t, ts, phone.token, "/api/v1/devices", nil); code != http.StatusForbidden {
		t.Fatalf("до аттестации: ожидался 403, получен %d", code)
	}
	if err := srv.Store.SetDeviceAttestation(ctx, phone.userID, phone.id, "verified", "{}"); err != nil {
		t.Fatal(err)
	}
	if code := getAuthed(t, ts, phone.token, "/api/v1/devices", nil); code != http.StatusOK {
		t.Fatalf("после аттестации: ожидался 200, получен %d", code)
	}

	// Телефон, вошедший в прежнюю личность без КПУ («до ДУ1»), просит КПУ — только после аттестации.
	srv.Attestation = trustRecord
	second, code, _ := registerProof(t, ts, "+79990000222", acc.identityPub(), nil)
	if code != http.StatusCreated {
		t.Fatalf("второй телефон: %d", code)
	}
	srv.Attestation = trustRequire
	b64 := base64.RawURLEncoding
	ask := map[string]any{
		"ask_pub":         b64.EncodeToString(acc.askPub()),
		"ask_sig":         b64.EncodeToString(ed25519.Sign(acc.identity, timacrypto.AskCertBytes(acc.askPub()))),
		"device_cert_sig": b64.EncodeToString(ed25519.Sign(acc.ask, timacrypto.DeviceCertBytes(second.encPub[:], second.signKey.Public().(ed25519.PublicKey)))),
	}
	var refusal struct {
		Code string `json:"code"`
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/users/me/signing-keys", second.token, ask, &refusal); code != http.StatusForbidden || refusal.Code != attestationRequired {
		t.Fatalf("КПУ без аттестации: %d %s", code, refusal.Code)
	}
	if err := srv.Store.SetDeviceAttestation(ctx, second.userID, second.id, "verified", "{}"); err != nil {
		t.Fatal(err)
	}
	if code := authedJSON(t, ts, "POST", "/api/v1/users/me/signing-keys", second.token, ask, nil); code != http.StatusOK {
		t.Fatalf("КПУ после аттестации: ожидался 200, получен %d", code)
	}
}
