package escrow

// Unit-тесты stub-анклава: Шамир (порог, повреждение), полный unseal-поток
// клиентским конвейером (encapsulate + hkdf + SecretBox — как EscrowModule),
// повторное открытие состояния, append-only аудит.

import (
	"bytes"
	"crypto/mlkem"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/nacl/secretbox"
)

func TestShamirSplitCombine(t *testing.T) {
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		t.Fatal(err)
	}
	shares, err := SplitSecret(secret, 5, 3)
	if err != nil || len(shares) != 5 {
		t.Fatalf("split: %v (%d долей)", err, len(shares))
	}
	// Любые 3 из 5 восстанавливают секрет
	combos := [][]int{{0, 1, 2}, {0, 2, 4}, {1, 3, 4}, {2, 3, 4}, {0, 1, 4}}
	for _, c := range combos {
		got, err := CombineShares([]string{shares[c[0]], shares[c[1]], shares[c[2]]})
		if err != nil || !bytes.Equal(got, secret) {
			t.Fatalf("комбинация %v не восстановила секрет (%v)", c, err)
		}
	}
	// 2 доли математически дают ДРУГОЙ «секрет» (интерполяция прямой) — порог
	// обеспечивает несовпадение, а авторизацию — сверка хэша в authorize
	got, err := CombineShares(shares[:2])
	if err == nil && bytes.Equal(got, secret) {
		t.Fatal("2 доли не должны восстанавливать секрет")
	}
	// Повреждённая доля → другой секрет
	bad := shares[2][:len(shares[2])-2] + "xx"
	got, err = CombineShares([]string{shares[0], shares[1], bad})
	if err == nil && bytes.Equal(got, secret) {
		t.Fatal("повреждённая доля не должна давать верный секрет")
	}
	// Мусор → ошибка формата
	if _, err := CombineShares([]string{"не-доля", shares[0], shares[1]}); err == nil {
		t.Fatal("мусорная доля обязана дать ошибку")
	}
}

// clientWrap повторяет клиентский EscrowModule.wrap (Kotlin) на Go.
func clientWrap(t *testing.T, pubKey, messageKey []byte) (ct, wrapped []byte) {
	t.Helper()
	ek, err := mlkem.NewEncapsulationKey768(pubKey)
	if err != nil {
		t.Fatal(err)
	}
	shared, ct := ek.Encapsulate()
	wrapKey := make([]byte, 32)
	if _, err := io.ReadFull(hkdf.New(sha256.New, shared, nil, []byte("tima/escrow/v1")), wrapKey); err != nil {
		t.Fatal(err)
	}
	var key [32]byte
	copy(key[:], wrapKey)
	var nonce [24]byte
	rand.Read(nonce[:])
	return ct, secretbox.Seal(nonce[:], messageKey, &nonce, &key)
}

func TestEnclaveUnsealEndToEnd(t *testing.T) {
	dir := t.TempDir()
	enc, shares, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(shares) != SharesN {
		t.Fatalf("инициализация обязана выдать %d долей, выдано %d", SharesN, len(shares))
	}

	mux := http.NewServeMux()
	enc.Register(mux)
	ts := httptest.NewServer(mux)
	defer ts.Close()

	// Публичный ключ — как его увидит клиент через tima
	resp, err := http.Get(ts.URL + "/v1/pubkey")
	if err != nil {
		t.Fatal(err)
	}
	var pub struct {
		Version   int    `json:"escrow_key_version"`
		PublicKey string `json:"public_key"`
	}
	_ = json.NewDecoder(resp.Body).Decode(&pub)
	resp.Body.Close()
	pubKey, err := base64.RawURLEncoding.DecodeString(pub.PublicKey)
	if err != nil || len(pubKey) != PubKeySize || pub.Version != 1 {
		t.Fatalf("pubkey: v%d, %d байт", pub.Version, len(pubKey))
	}

	// «Клиент» оборачивает message_key на escrow — слой 2 конверта
	messageKey := make([]byte, 32)
	rand.Read(messageKey)
	ct, wrapped := clientWrap(t, pubKey, messageKey)

	b64 := base64.RawURLEncoding
	unseal := func(shares []string) (*http.Response, []string) {
		body, _ := json.Marshal(map[string]any{
			"shares": shares,
			"reason": "тестовый запрос №1",
			"blobs":  []map[string]string{{"mlkem_ct": b64.EncodeToString(ct), "wrapped_key": b64.EncodeToString(wrapped)}},
		})
		resp, err := http.Post(ts.URL+"/v1/unseal", "application/json", bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var out struct {
			Keys []string `json:"keys"`
		}
		_ = json.NewDecoder(resp.Body).Decode(&out)
		return resp, out.Keys
	}

	// 2 доли → 403; 3 доли → message_key восстановлен байт-в-байт
	if resp, _ := unseal(shares[:2]); resp.StatusCode != http.StatusForbidden {
		t.Fatalf("2 доли: ожидался 403, получен %d", resp.StatusCode)
	}
	resp2, keys := unseal([]string{shares[4], shares[1], shares[3]})
	if resp2.StatusCode != http.StatusOK || len(keys) != 1 {
		t.Fatalf("unseal: %d, ключей %d", resp2.StatusCode, len(keys))
	}
	got, _ := b64.DecodeString(keys[0])
	if !bytes.Equal(got, messageKey) {
		t.Fatal("восстановленный message_key не совпал")
	}

	// Аудит: обе операции записаны (denied + ok)
	raw, err := os.ReadFile(filepath.Join(dir, "escrow-audit.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(string(raw)), "\n")
	if len(lines) != 2 || !strings.Contains(lines[0], "unseal_denied") || !strings.Contains(lines[1], "unseal_ok") {
		t.Fatalf("аудит: %q", lines)
	}

	// Повторное открытие: тот же ключ, доли повторно НЕ выдаются
	enc2, again, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if again != nil {
		t.Fatal("повторный Open не должен выдавать доли")
	}
	if !bytes.Equal(enc2.PublicKey(), enc.PublicKey()) {
		t.Fatal("публичный ключ обязан сохраняться между запусками")
	}
}
