package escrow

// Подпись конфига анклава (Р2): без неё компрометация бэкенда позволяет
// подменить public_key, который клиент получает через /api/v1/escrow/*.
// Здесь проверяется ровно то, что решает задачу — подпись покрывает реальные
// поля ответа (тамперинг ловится), переживает перезапуск анклава и
// дозаписывается в состояния, заведённые до этого поля.

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"testing"
)

func TestKeyMetaSignatureVerifies(t *testing.T) {
	env := newKeyringEnv(t)
	var meta signedKeyMeta
	if code := env.post(t, "/v1/key",
		map[string]string{"region": "ru", "epoch": "2026-07", "chat_id": "chat-sig"}, &meta); code != 200 {
		t.Fatalf("/v1/key: %d", code)
	}
	if meta.Signature == "" {
		t.Fatal("ответ анклава не подписан")
	}
	sig, err := base64.RawURLEncoding.DecodeString(meta.Signature)
	if err != nil {
		t.Fatal(err)
	}
	msg, err := KeyMetaSigningBytes(meta.KeyMeta)
	if err != nil {
		t.Fatal(err)
	}
	if !ed25519.Verify(env.enc.SigningPublicKey(), msg, sig) {
		t.Fatal("подпись не проходит проверку зашитым публичным ключом анклава")
	}

	// GET /v1/keys/{id} подписывает ту же запись тем же ключом.
	var again signedKeyMeta
	resp, err := http.Get(env.ts.URL + "/v1/keys/" + strconv.FormatUint(uint64(meta.ID), 10))
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if err := json.NewDecoder(resp.Body).Decode(&again); err != nil {
		t.Fatal(err)
	}
	if again.Signature != meta.Signature {
		t.Fatal("/v1/keys/{id} подписал ту же запись иначе, чем /v1/key")
	}
}

// Ровно тот сценарий, который защита закрывает: скомпрометированный бэкенд
// подменяет public_key на пути к клиенту. Старая подпись новому значению
// соответствовать не должна — иначе подмену нечем ловить.
func TestKeyMetaSignatureRejectsTampering(t *testing.T) {
	env := newKeyringEnv(t)
	var meta signedKeyMeta
	env.post(t, "/v1/key", map[string]string{"region": "ru", "epoch": "2026-07", "chat_id": "chat-tamper"}, &meta)
	sig, err := base64.RawURLEncoding.DecodeString(meta.Signature)
	if err != nil {
		t.Fatal(err)
	}

	tampered := meta.KeyMeta
	otherPub := mustDecode(t, tampered.PublicKey)
	otherPub[0] ^= 0xff
	tampered.PublicKey = base64.RawURLEncoding.EncodeToString(otherPub)

	msg, err := KeyMetaSigningBytes(tampered)
	if err != nil {
		t.Fatal(err)
	}
	if ed25519.Verify(env.enc.SigningPublicKey(), msg, sig) {
		t.Fatal("подпись прошла проверку для подменённого публичного ключа")
	}
}

func TestLegacyPubkeySignatureVerifies(t *testing.T) {
	env := newKeyringEnv(t)
	var got struct {
		Version   int    `json:"escrow_key_version"`
		PublicKey string `json:"public_key"`
		Signature string `json:"signature"`
	}
	resp, err := http.Get(env.ts.URL + "/v1/pubkey")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if err := json.NewDecoder(resp.Body).Decode(&got); err != nil {
		t.Fatal(err)
	}
	pub := mustDecode(t, got.PublicKey)
	sig := mustDecode(t, got.Signature)
	if !ed25519.Verify(env.enc.SigningPublicKey(), PubkeySigningBytes(got.Version, pub), sig) {
		t.Fatal("подпись легаси /v1/pubkey не проходит проверку")
	}
}

// Ключ подписи переживает перезапуск процесса (читается со стейт-файла), а не
// генерируется заново при каждом Open — иначе клиент, зашивший старый публичный
// ключ, отваливался бы после первого рестарта анклава.
func TestSigningKeyPersistsAcrossReopen(t *testing.T) {
	dir := t.TempDir()
	enc1, _, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	enc2, _, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(enc1.SigningPublicKey(), enc2.SigningPublicKey()) {
		t.Fatal("ключ подписи изменился при повторном открытии того же каталога")
	}
}

// Состояния анклава, заведённые до этого поля, ключа подписи не имеют —
// открытие обязано дозаписать его, а не отказаться работать.
func TestSigningKeyAddedToLegacyState(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := Open(dir); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(dir, stateFile)
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var m map[string]any
	if err := json.Unmarshal(raw, &m); err != nil {
		t.Fatal(err)
	}
	delete(m, "signing_seed")
	delete(m, "signing_pub")
	raw2, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, raw2, 0o600); err != nil {
		t.Fatal(err)
	}

	enc, shares, err := Open(dir)
	if err != nil {
		t.Fatalf("открытие устаревшего состояния: %v", err)
	}
	if shares != nil {
		t.Fatal("повторное открытие не должно возвращать доли Шамира заново")
	}
	if len(enc.SigningPublicKey()) != ed25519.PublicKeySize {
		t.Fatalf("ключ подписи не создан: %d байт", len(enc.SigningPublicKey()))
	}

	// И дозаписанный ключ обязан пережить следующее открытие, а не потеряться.
	enc2, _, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(enc.SigningPublicKey(), enc2.SigningPublicKey()) {
		t.Fatal("дозаписанный ключ подписи не сохранился между открытиями")
	}
}

func mustDecode(t *testing.T, s string) []byte {
	t.Helper()
	b, err := base64.RawURLEncoding.DecodeString(s)
	if err != nil {
		t.Fatal(err)
	}
	return b
}
