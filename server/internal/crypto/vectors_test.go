package crypto

// KAT против schema/test-vectors/vectors.json — те же векторы, что проходит
// Kotlin-реализация (messenger-crypto). Паритет двух реализаций и есть контракт.

import (
	"bytes"
	"crypto/ed25519"
	"crypto/mlkem"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"testing"

	"golang.org/x/crypto/curve25519"
	"golang.org/x/crypto/hkdf"
	"golang.org/x/crypto/nacl/box"
	"golang.org/x/crypto/nacl/secretbox"
)

func loadVectors(t *testing.T) map[string]json.RawMessage {
	t.Helper()
	raw, err := os.ReadFile("../../../schema/test-vectors/vectors.json")
	if err != nil {
		t.Fatalf("vectors.json не найден: %v", err)
	}
	var file struct {
		Vectors map[string]json.RawMessage `json:"vectors"`
	}
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatalf("vectors.json не парсится: %v", err)
	}
	return file.Vectors
}

func vec[T any](t *testing.T, vectors map[string]json.RawMessage, name string) T {
	t.Helper()
	rawVec, ok := vectors[name]
	if !ok {
		t.Fatalf("вектор %q отсутствует", name)
	}
	var v T
	if err := json.Unmarshal(rawVec, &v); err != nil {
		t.Fatalf("вектор %q не парсится: %v", name, err)
	}
	return v
}

func unhex(t *testing.T, s string) []byte {
	t.Helper()
	b, err := hex.DecodeString(s)
	if err != nil {
		t.Fatalf("плохой hex: %v", err)
	}
	return b
}

func assertHex(t *testing.T, want string, got []byte, what string) {
	t.Helper()
	if hex.EncodeToString(got) != want {
		t.Fatalf("%s разошёлся с эталоном:\n want %s\n got  %s", what, want, hex.EncodeToString(got))
	}
}

func TestCanonicalBytesVector(t *testing.T) {
	vectors := loadVectors(t)
	v := vec[struct {
		Inputs struct {
			FormatVersion   uint32 `json:"format_version"`
			MessageID       uint64 `json:"message_id"`
			ChatID          string `json:"chat_id"`
			SenderID        string `json:"sender_id"`
			SenderDevice    string `json:"sender_device"`
			Kind            uint32 `json:"kind"`
			CreatedAtUnixMs int64  `json:"created_at_unix_ms"`
			ReplyTo         uint64 `json:"reply_to"`
		} `json:"inputs"`
		CanonicalBytesHex string `json:"canonical_bytes_hex"`
		Sha256Hex         string `json:"sha256_hex"`
	}](t, vectors, "canonical_bytes")

	sb := vec[struct {
		KodiumOutputHex string `json:"kodium_output_hex"`
	}](t, vectors, "secretbox")
	bw := vec[struct {
		EphPublic string `json:"eph_public"`
	}](t, vectors, "box_wrap")

	// Блобы — как в генераторе: payload из secretbox, escrow = 0xa1×1088 ‖ 0xa2×48
	escrowBytes := append(bytes.Repeat([]byte{0xa1}, 1088), bytes.Repeat([]byte{0xa2}, 48)...)
	cb := CanonicalBytes(v.Inputs.FormatVersion, EnvelopeMeta{
		MessageID:       v.Inputs.MessageID,
		ChatID:          v.Inputs.ChatID,
		SenderID:        v.Inputs.SenderID,
		SenderDevice:    v.Inputs.SenderDevice,
		Kind:            v.Inputs.Kind,
		CreatedAtUnixMs: v.Inputs.CreatedAtUnixMs,
		ReplyTo:         v.Inputs.ReplyTo,
	}, unhex(t, sb.KodiumOutputHex), escrowBytes, unhex(t, bw.EphPublic), nil)

	assertHex(t, v.CanonicalBytesHex, cb, "canonical_bytes")
	digest := sha256.Sum256(cb)
	assertHex(t, v.Sha256Hex, digest[:], "sha256(canonical_bytes)")
}

func TestEd25519Vector(t *testing.T) {
	v := vec[struct {
		Seed         string `json:"seed"`
		PublicKey    string `json:"public_key"`
		MessageHex   string `json:"message_hex"`
		SignatureHex string `json:"signature_hex"`
	}](t, loadVectors(t), "ed25519")

	key := ed25519.NewKeyFromSeed(unhex(t, v.Seed))
	assertHex(t, v.PublicKey, key.Public().(ed25519.PublicKey), "ed25519 public key")

	sig := ed25519.Sign(key, unhex(t, v.MessageHex))
	assertHex(t, v.SignatureHex, sig, "ed25519 signature")

	if !VerifyEnvelopeSignature(unhex(t, v.PublicKey), unhex(t, v.MessageHex), sig) {
		t.Fatal("VerifyEnvelopeSignature обязан принять валидную подпись")
	}
	tampered := append([]byte{}, unhex(t, v.MessageHex)...)
	tampered[0] ^= 1
	if VerifyEnvelopeSignature(unhex(t, v.PublicKey), tampered, sig) {
		t.Fatal("VerifyEnvelopeSignature принял подпись повреждённого сообщения")
	}
}

func TestSecretboxVector(t *testing.T) {
	v := vec[struct {
		Key             string `json:"key"`
		Nonce           string `json:"nonce"`
		PlaintextHex    string `json:"plaintext_hex"`
		KodiumOutputHex string `json:"kodium_output_hex"`
	}](t, loadVectors(t), "secretbox")

	var key [32]byte
	var nonce [24]byte
	copy(key[:], unhex(t, v.Key))
	copy(nonce[:], unhex(t, v.Nonce))
	out := secretbox.Seal(nonce[:], unhex(t, v.PlaintextHex), &nonce, &key) // nonce‖box, как у Kodium
	assertHex(t, v.KodiumOutputHex, out, "secretbox nonce‖box")
}

func TestBoxWrapVector(t *testing.T) {
	v := vec[struct {
		EphSecret       string `json:"eph_secret"`
		EphPublic       string `json:"eph_public"`
		RecipientPublic string `json:"recipient_public"`
		Nonce           string `json:"nonce"`
		MessageKey      string `json:"message_key"`
		KodiumOutputHex string `json:"kodium_output_hex"`
	}](t, loadVectors(t), "box_wrap")

	ephPub, err := curve25519.X25519(unhex(t, v.EphSecret), curve25519.Basepoint)
	if err != nil {
		t.Fatal(err)
	}
	assertHex(t, v.EphPublic, ephPub, "X25519 public из секрета")

	var secret, recipientPub [32]byte
	var nonce [24]byte
	copy(secret[:], unhex(t, v.EphSecret))
	copy(recipientPub[:], unhex(t, v.RecipientPublic))
	copy(nonce[:], unhex(t, v.Nonce))
	out := box.Seal(nonce[:], unhex(t, v.MessageKey), &nonce, &recipientPub, &secret)
	assertHex(t, v.KodiumOutputHex, out, "box_wrap nonce‖box")
}

func TestHKDFVector(t *testing.T) {
	v := vec[struct {
		IKM       string `json:"ikm"`
		Salt      string `json:"salt"`
		InfoHex   string `json:"info_hex"`
		OutputHex string `json:"output_hex"`
	}](t, loadVectors(t), "hkdf_sha256")

	out := make([]byte, 32)
	r := hkdf.New(sha256.New, unhex(t, v.IKM), unhex(t, v.Salt), unhex(t, v.InfoHex))
	if _, err := io.ReadFull(r, out); err != nil {
		t.Fatal(err)
	}
	assertHex(t, v.OutputHex, out, "hkdf_sha256")
}

func TestMediaChunkKeysVector(t *testing.T) {
	v := vec[struct {
		MediaKey string `json:"media_key"`
		Chunk0   string `json:"chunk_0"`
		Chunk1   string `json:"chunk_1"`
		Chunk10  string `json:"chunk_10"`
	}](t, loadVectors(t), "media_chunk_keys")

	derive := func(info string) []byte {
		out := make([]byte, 32)
		r := hkdf.New(sha256.New, unhex(t, v.MediaKey), nil, []byte(info)) // salt пустой → нули
		if _, err := io.ReadFull(r, out); err != nil {
			t.Fatal(err)
		}
		return out
	}
	assertHex(t, v.Chunk0, derive("chunk:0"), "chunk_key[0]")
	assertHex(t, v.Chunk1, derive("chunk:1"), "chunk_key[1]")
	assertHex(t, v.Chunk10, derive("chunk:10"), "chunk_key[10]")
}

func TestMLKEM768Vector(t *testing.T) {
	v := vec[struct {
		KeygenSeed      string `json:"keygen_seed"`
		PublicKeyLen    int    `json:"public_key_len"`
		CiphertextLen   int    `json:"ciphertext_len"`
		SharedLen       int    `json:"shared_len"`
		PublicKeySha256 string `json:"public_key_sha256"`
	}](t, loadVectors(t), "mlkem768_escrow")

	dk, err := mlkem.NewDecapsulationKey768(unhex(t, v.KeygenSeed)) // seed = d(32) ‖ z(32)
	if err != nil {
		t.Fatal(err)
	}
	ekBytes := dk.EncapsulationKey().Bytes()
	if len(ekBytes) != v.PublicKeyLen {
		t.Fatalf("длина ek: ожидалось %d, получено %d", v.PublicKeyLen, len(ekBytes))
	}
	digest := sha256.Sum256(ekBytes)
	assertHex(t, v.PublicKeySha256, digest[:], "sha256(ml-kem ek) — keygen(seed)")

	// Инвариант: encapsulate → decapsulate восстанавливает shared (ct рандомизирован)
	shared, ct := dk.EncapsulationKey().Encapsulate()
	if len(ct) != v.CiphertextLen || len(shared) != v.SharedLen {
		t.Fatalf("размеры ct/shared: %d/%d", len(ct), len(shared))
	}
	shared2, err := dk.Decapsulate(ct)
	if err != nil || !bytes.Equal(shared, shared2) {
		t.Fatalf("decapsulate не восстановил shared (err=%v)", err)
	}
}
