package escrow

// Сквозная проверка пути эпох через HTTP: выдача ключа области → шифрование на него
// клиентским конвейером → юридический доступ по долям → уничтожение по сроку.

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
	"testing"
	"time"

	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/nacl/secretbox"
)

type keyringEnv struct {
	ts     *httptest.Server
	enc    *Enclave
	ring   *Keyring
	shares []string
}

func newKeyringEnv(t *testing.T) *keyringEnv {
	t.Helper()
	dir := t.TempDir()
	enc, shares, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	ring, err := OpenKeyring(dir, halfYear)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	enc.Register(mux)
	enc.RegisterKeyring(mux, ring)
	ts := httptest.NewServer(mux)
	t.Cleanup(ts.Close)
	return &keyringEnv{ts: ts, enc: enc, ring: ring, shares: shares}
}

func (e *keyringEnv) post(t *testing.T, path string, body, out any) int {
	t.Helper()
	raw, _ := json.Marshal(body)
	resp, err := http.Post(e.ts.URL+path, "application/json", bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if out != nil {
		_ = json.NewDecoder(resp.Body).Decode(out)
	} else {
		_, _ = io.Copy(io.Discard, resp.Body)
	}
	return resp.StatusCode
}

// sealToEscrow повторяет клиентский конвейер (crypto-protocol.md §6):
// encapsulate на публичный ключ эпохи, HKDF, SecretBox поверх message_key.
func sealToEscrow(t *testing.T, pubB64 string, messageKey []byte) (ctB64, wrappedB64 string) {
	t.Helper()
	pub, err := base64.RawURLEncoding.DecodeString(pubB64)
	if err != nil {
		t.Fatal(err)
	}
	ek, err := mlkem.NewEncapsulationKey768(pub)
	if err != nil {
		t.Fatal(err)
	}
	shared, ct := ek.Encapsulate()
	wrapKey := make([]byte, 32)
	if _, err := io.ReadFull(hkdf.New(sha256.New, shared, nil, []byte(hkdfInfo)), wrapKey); err != nil {
		t.Fatal(err)
	}
	var key [32]byte
	copy(key[:], wrapKey)
	var nonce [24]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		t.Fatal(err)
	}
	wrapped := secretbox.Seal(nonce[:], messageKey, &nonce, &key)
	b64 := base64.RawURLEncoding
	return b64.EncodeToString(ct), b64.EncodeToString(wrapped)
}

func TestKeyringHTTPEndToEnd(t *testing.T) {
	env := newKeyringEnv(t)

	// 1. Ключ области выдаётся и не меняется при повторном запросе.
	var meta KeyMeta
	if code := env.post(t, "/v1/key",
		map[string]string{"region": "ru", "epoch": "2026-07", "chat_id": "chat-1"}, &meta); code != 200 {
		t.Fatalf("/v1/key: %d", code)
	}
	if meta.ID == 0 || meta.PublicKey == "" {
		t.Fatalf("пустой ключ: %+v", meta)
	}
	var again KeyMeta
	env.post(t, "/v1/key", map[string]string{"region": "ru", "epoch": "2026-07", "chat_id": "chat-1"}, &again)
	if again.ID != meta.ID {
		t.Fatal("повторный запрос выдал другой ключ")
	}

	// 2. Шифруем ключ сообщения на этот ключ эпохи.
	messageKey := make([]byte, 32)
	if _, err := rand.Read(messageKey); err != nil {
		t.Fatal(err)
	}
	ct, wrapped := sealToEscrow(t, meta.PublicKey, messageKey)

	blob := map[string]any{"key_id": meta.ID, "mlkem_ct": ct, "wrapped_key": wrapped}

	// 3. Без долей доступа нет.
	if code := env.post(t, "/v1/unseal-scoped", map[string]any{
		"reason": "дело 1", "blobs": []any{blob},
	}, nil); code != http.StatusForbidden {
		t.Fatalf("без долей: %d, ожидали 403", code)
	}

	// 4. С долями — отдаётся ключ сообщения, но не приватный ключ эпохи.
	var ok struct {
		Keys []string `json:"keys"`
	}
	if code := env.post(t, "/v1/unseal-scoped", map[string]any{
		"shares": env.shares[:SharesK], "reason": "дело 1", "blobs": []any{blob},
	}, &ok); code != 200 {
		t.Fatalf("с долями: %d", code)
	}
	got, err := base64.RawURLEncoding.DecodeString(ok.Keys[0])
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, messageKey) {
		t.Fatal("вернулся не тот ключ сообщения")
	}

	// 5. После уничтожения по сроку — 410: расшифровать нечем, и это штатный исход.
	if _, err := env.enc.SweepExpired(env.ring, meta.DestroyAt.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	if code := env.post(t, "/v1/unseal-scoped", map[string]any{
		"shares": env.shares[:SharesK], "reason": "дело 2", "blobs": []any{blob},
	}, nil); code != http.StatusGone {
		t.Fatalf("после уничтожения: %d, ожидали 410", code)
	}
}

// Приватный ключ не отдаётся ни одним эндпоинтом — это инвариант, а не политика.
func TestKeyringNeverExposesPrivateKey(t *testing.T) {
	env := newKeyringEnv(t)
	var meta KeyMeta
	env.post(t, "/v1/key", map[string]string{"region": "ru", "epoch": "2026-07", "chat_id": "c"}, &meta)

	seed, err := env.ring.Seed(meta.ID)
	if err != nil {
		t.Fatal(err)
	}
	seedB64 := base64.RawURLEncoding.EncodeToString(seed)

	for _, path := range []string{"/v1/key", "/v1/keys/1"} {
		var raw bytes.Buffer
		var resp *http.Response
		if path == "/v1/key" {
			body, _ := json.Marshal(map[string]string{"region": "ru", "epoch": "2026-07", "chat_id": "c"})
			resp, err = http.Post(env.ts.URL+path, "application/json", bytes.NewReader(body))
		} else {
			resp, err = http.Get(env.ts.URL + path)
		}
		if err != nil {
			t.Fatal(err)
		}
		_, _ = raw.ReadFrom(resp.Body)
		resp.Body.Close()
		if bytes.Contains(raw.Bytes(), []byte(seedB64)) {
			t.Fatalf("%s отдал приватный seed", path)
		}
		if bytes.Contains(raw.Bytes(), []byte(`"seed"`)) {
			t.Fatalf("%s отдал поле seed", path)
		}
	}
}

// Уничтоженный ключ и никогда не существовавший неотличимы снаружи: анклав не
// рассказывает, что у него когда-то было.
func TestKeyMetaHidesDestroyed(t *testing.T) {
	env := newKeyringEnv(t)
	var meta KeyMeta
	env.post(t, "/v1/key", map[string]string{"region": "ru", "epoch": "2026-01", "chat_id": "c"}, &meta)
	if _, err := env.enc.SweepExpired(env.ring, meta.DestroyAt.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	resp, err := http.Get(env.ts.URL + "/v1/keys/999999")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	missing := resp.StatusCode

	resp2, err := http.Get(env.ts.URL + "/v1/keys/1")
	if err != nil {
		t.Fatal(err)
	}
	resp2.Body.Close()
	if resp2.StatusCode != missing {
		t.Fatalf("уничтоженный ключ отвечает %d, несуществующий %d — состояния различимы",
			resp2.StatusCode, missing)
	}
}

// Уничтожение попадает в аудит: без записи «мы удалили» остаётся обещанием.
func TestDestructionIsAudited(t *testing.T) {
	env := newKeyringEnv(t)
	var meta KeyMeta
	env.post(t, "/v1/key", map[string]string{"region": "ru", "epoch": "2026-01", "chat_id": "c"}, &meta)
	if _, err := env.enc.SweepExpired(env.ring, meta.DestroyAt.Add(time.Hour)); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(env.enc.dir, auditFile))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(raw, []byte("keys_destroyed")) {
		t.Fatalf("в аудите нет записи об уничтожении:\n%s", raw)
	}
}
