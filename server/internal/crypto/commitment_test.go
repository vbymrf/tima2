package crypto

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"testing"
)

func TestKeyCommitmentIsDeterministicAndBound(t *testing.T) {
	key := bytes.Repeat([]byte{7}, 32)
	a := KeyCommitment(key)
	if len(a) != CommitmentSize {
		t.Fatalf("длина %d, ожидали %d", len(a), CommitmentSize)
	}
	if !bytes.Equal(a, KeyCommitment(key)) {
		t.Fatal("обязательство не детерминировано — получатель не сможет сверить")
	}
	other := bytes.Repeat([]byte{7}, 32)
	other[31] ^= 1
	if bytes.Equal(a, KeyCommitment(other)) {
		t.Fatal("разные ключи дали одно обязательство")
	}
	if bytes.Contains(a, key[:8]) {
		t.Fatal("обязательство раскрывает часть ключа")
	}
}

// Раскладка v1 обязана остаться байт-в-байт прежней: иначе старые сообщения
// перестанут проверяться, а KAT-векторы — сходиться.
func TestV1LayoutUnchanged(t *testing.T) {
	meta := EnvelopeMeta{MessageID: 42, ChatID: "c", SenderID: "s", SenderDevice: "d", Kind: 1, CreatedAtUnixMs: 1750000000000}
	payload := []byte("p")
	old := CanonicalBytes(1, meta, payload, []byte("e"), []byte("k"), nil)
	withCommit := CanonicalBytesV2(1, meta, payload, []byte("e"), []byte("k"), nil, bytes.Repeat([]byte{9}, 32))
	if !bytes.Equal(old, withCommit) {
		t.Fatal("в v1 обязательство попало в preimage — старые подписи сломаются")
	}
}

// В v2 обязательство входит в подписываемые байты: подменить его нельзя.
func TestV2IncludesCommitment(t *testing.T) {
	meta := EnvelopeMeta{MessageID: 42, ChatID: "c", SenderID: "s", SenderDevice: "d"}
	a := CanonicalBytesV2(2, meta, nil, nil, nil, nil, bytes.Repeat([]byte{1}, 32))
	b := CanonicalBytesV2(2, meta, nil, nil, nil, nil, bytes.Repeat([]byte{2}, 32))
	if bytes.Equal(a, b) {
		t.Fatal("preimage не зависит от обязательства — его можно подменить безнаказанно")
	}
	v1 := CanonicalBytesV2(1, meta, nil, nil, nil, nil, bytes.Repeat([]byte{1}, 32))
	if len(a) != len(v1)+CommitmentSize {
		t.Fatalf("v2 длиннее v1 на %d, ожидали %d", len(a)-len(v1), CommitmentSize)
	}
}

func TestVerifyRejectsNonCanonicalS(t *testing.T) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	msg := []byte("сообщение")
	sig := ed25519.Sign(priv, msg)
	if !VerifyEnvelopeSignature(pub, msg, sig) {
		t.Fatal("нормальная подпись отвергнута")
	}

	// S = L: ровно граница, тоже неканонично.
	atOrder := append(append([]byte{}, sig[:32]...), order[:]...)
	if VerifyEnvelopeSignature(pub, msg, atOrder) {
		t.Fatal("принята подпись с S == L")
	}
	// S со старшим байтом выше порядка — заведомо неканонично.
	big := append(append([]byte{}, sig[:32]...), sig[32:]...)
	big[63] = 0xff
	if VerifyEnvelopeSignature(pub, msg, big) {
		t.Fatal("принята подпись с неканоническим S")
	}
}

func TestIsCanonicalS(t *testing.T) {
	small := make([]byte, 32)
	if !isCanonicalS(small) {
		t.Fatal("S = 0 должен считаться каноничным по величине")
	}
	if isCanonicalS(order[:]) {
		t.Fatal("S = L неканоничен")
	}
	justBelow := order
	justBelow[0]--
	if !isCanonicalS(justBelow[:]) {
		t.Fatal("S = L-1 каноничен")
	}
	if isCanonicalS(make([]byte, 31)) {
		t.Fatal("принята S неверной длины")
	}
}
