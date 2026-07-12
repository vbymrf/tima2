// Package crypto — серверная сторона крипто-контракта TIMA.
//
// Сервер НЕ расшифровывает контент (crypto-protocol.md §10): его крипто-обязанности —
// собрать canonical_bytes из полей конверта и проверить подпись Ed25519 при приёме.
// Раскладка canonical_bytes зафиксирована в schema/proto/README.md и KAT-вектором
// canonical_bytes; расхождение с Kotlin-реализацией = красный билд.
package crypto

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/binary"
)

// FormatVersion — текущая версия раскладки canonical_bytes (envelope.proto format_version).
const FormatVersion = 1

// EnvelopeMeta — plaintext-метаданные конверта (Metadata из envelope.proto).
type EnvelopeMeta struct {
	MessageID       uint64
	ChatID          string
	SenderID        string
	SenderDevice    string
	Kind            uint32
	CreatedAtUnixMs int64
	ReplyTo         uint64
}

// CanonicalBytes собирает подписываемый preimage: строго schema/proto/README.md.
// Все целые — little-endian; строки — UTF-8 с длинным префиксом u32; блобы — их sha256.
// escrowBytes = mlkem_ct ‖ wrapped_message_key; ratchetEnvelope может быть пустым
// (тогда хэшируются пустые байты). wrapped_keys в preimage не входят.
func CanonicalBytes(formatVersion uint32, meta EnvelopeMeta, encryptedPayload, escrowBytes, senderEphemeralPub, ratchetEnvelope []byte) []byte {
	var out []byte
	out = appendU32(out, formatVersion)
	out = appendU64(out, meta.MessageID)
	out = appendLP(out, meta.ChatID)
	out = appendLP(out, meta.SenderID)
	out = appendLP(out, meta.SenderDevice)
	out = appendU32(out, meta.Kind)
	out = appendU64(out, uint64(meta.CreatedAtUnixMs))
	out = appendU64(out, meta.ReplyTo)
	for _, blob := range [][]byte{encryptedPayload, escrowBytes, senderEphemeralPub, ratchetEnvelope} {
		h := sha256.Sum256(blob)
		out = append(out, h[:]...)
	}
	return out
}

// VerifyEnvelopeSignature проверяет detached-подпись Ed25519 устройства отправителя
// над canonical_bytes (публичный ключ — из devices).
func VerifyEnvelopeSignature(senderSigningPub, canonicalBytes, signature []byte) bool {
	if len(senderSigningPub) != ed25519.PublicKeySize || len(signature) != ed25519.SignatureSize {
		return false
	}
	return ed25519.Verify(ed25519.PublicKey(senderSigningPub), canonicalBytes, signature)
}

func appendU32(b []byte, v uint32) []byte { return binary.LittleEndian.AppendUint32(b, v) }
func appendU64(b []byte, v uint64) []byte { return binary.LittleEndian.AppendUint64(b, v) }

func appendLP(b []byte, s string) []byte {
	b = appendU32(b, uint32(len(s)))
	return append(b, s...)
}
