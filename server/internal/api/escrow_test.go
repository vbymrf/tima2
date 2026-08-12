package api

// Интеграционный тест прокси /escrow/pubkey: tima отдаёт клиенту ключ
// stub-анклава, клиентский encapsulate на него работает.

import (
	"crypto/ed25519"
	"crypto/mlkem"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"testing"

	"tima/server/internal/escrow"
)

func TestEscrowPubkeyProxy(t *testing.T) {
	ts, srv := setup(t)
	user := registerDevice(t, ts, "+79991110001")

	// Без ESCROW_URL → 503
	if code := authedJSON(t, ts, "GET", "/api/v1/escrow/pubkey", user.token, nil, nil); code != http.StatusServiceUnavailable {
		t.Fatalf("без анклава: ожидался 503, получен %d", code)
	}

	enc, _, err := escrow.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	enc.Register(mux)
	stub := httptest.NewServer(mux)
	defer stub.Close()
	srv.EscrowURL = stub.URL

	var resp struct {
		Version   int    `json:"escrow_key_version"`
		PublicKey string `json:"public_key"`
		Signature string `json:"signature"`
	}
	if code := authedJSON(t, ts, "GET", "/api/v1/escrow/pubkey", user.token, nil, &resp); code != http.StatusOK {
		t.Fatalf("pubkey через tima: %d", code)
	}
	pubKey, err := base64.RawURLEncoding.DecodeString(resp.PublicKey)
	if err != nil || len(pubKey) != escrow.PubKeySize || resp.Version != 1 {
		t.Fatalf("pubkey: v%d, %d байт", resp.Version, len(pubKey))
	}
	// Клиент сможет инкапсулировать на этот ключ (слой 2 конверта)
	if _, err := mlkem.NewEncapsulationKey768(pubKey); err != nil {
		t.Fatalf("ключ не годится для encapsulate: %v", err)
	}

	// Подпись анклава (Р2) проходит через tima без изменений — проверяется тем же
	// публичным ключом, каким подписывал сам анклав.
	sig, err := base64.RawURLEncoding.DecodeString(resp.Signature)
	if err != nil {
		t.Fatalf("signature не base64url: %v", err)
	}
	if !ed25519.Verify(enc.SigningPublicKey(), escrow.PubkeySigningBytes(resp.Version, pubKey), sig) {
		t.Fatal("подпись, пришедшая через tima, не проходит проверку")
	}

	// Кэш: анклав погашен, ключ всё ещё отдаётся
	stub.Close()
	if code := authedJSON(t, ts, "GET", "/api/v1/escrow/pubkey", user.token, nil, &resp); code != http.StatusOK {
		t.Fatalf("кэш после падения анклава: %d", code)
	}
}
