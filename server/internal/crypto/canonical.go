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
	"io"

	"golang.org/x/crypto/hkdf"
)

// FormatVersion — текущая версия раскладки canonical_bytes (envelope.proto format_version).
//
// v1 — исходная раскладка.
// v2 — добавлен key_commitment в конец preimage (ADR-0013): доказательство, что все
//
//	пути доставки ключа ведут к одному и тому же message_key.
const FormatVersion = 2

// FormatVersionLegacy — версия, которую приёмная сторона обязана продолжать
// принимать, пока в пути есть сообщения от старых клиентов.
const FormatVersionLegacy = 1

// CommitmentSize — длина key_commitment в байтах (HKDF-SHA256).
const CommitmentSize = 32

// commitmentInfo — доменная метка деривации. Отличается от escrow-инфо намеренно:
// одно и то же значение не должно годиться в двух ролях.
const commitmentInfo = "tima/commit/v1"

// KeyCommitment — обязательство по ключу сообщения.
//
// Зачем: message_key едет получателю несколькими независимыми путями — ratchet,
// wrapped_key на устройство и escrow_blob. Ни один из них не доказывает, что
// остальные ведут к тому же ключу. Poly1305 в SecretBox не является
// key-committing, поэтому один шифртекст может валидно раскрыться под ДВУМЯ
// разными ключами. Отправитель мог бы собрать сообщение так, что по ордеру
// расшифруется один текст, а у получателя на экране будет другой — и это ломает
// сам смысл escrow как доказательства.
//
// Обязательство входит в подписываемые байты, поэтому подменить его нельзя, а
// получатель на ЛЮБОМ пути пересчитывает его из добытого ключа и сверяет.
func KeyCommitment(messageKey []byte) []byte {
	return hkdfSHA256(messageKey, []byte(commitmentInfo), CommitmentSize)
}

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
//
// v2 добавляет key_commitment ХВОСТОМ: раскладка v1 остаётся байт-в-байт прежней,
// поэтому старые векторы продолжают проходить, а новое поле нельзя подменить —
// оно под подписью.
func CanonicalBytes(formatVersion uint32, meta EnvelopeMeta, encryptedPayload, escrowBytes, senderEphemeralPub, ratchetEnvelope []byte) []byte {
	return CanonicalBytesV2(formatVersion, meta, encryptedPayload, escrowBytes, senderEphemeralPub, ratchetEnvelope, nil)
}

// CanonicalBytesV2 — preimage с обязательством по ключу. Для formatVersion == 1
// keyCommitment игнорируется.
func CanonicalBytesV2(formatVersion uint32, meta EnvelopeMeta, encryptedPayload, escrowBytes, senderEphemeralPub, ratchetEnvelope, keyCommitment []byte) []byte {
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
	if formatVersion >= 2 {
		out = append(out, keyCommitment...)
	}
	return out
}

// hkdfSHA256 — HKDF-Extract+Expand без соли, как в контракте escrow (crypto-protocol.md §6).
func hkdfSHA256(ikm, info []byte, length int) []byte {
	out := make([]byte, length)
	if _, err := io.ReadFull(hkdf.New(sha256.New, ikm, nil, info), out); err != nil {
		panic("hkdf: " + err.Error()) // невозможно при length ≤ 255*32
	}
	return out
}

// GroupMessageDomain — доменная метка preimage сообщения группы; несёт версию
// раскладки (schema/proto/README.md §group_message_canonical_bytes).
const GroupMessageDomain = "tima.group_message.v1"

// GroupMessageMeta — подписываемые поля сообщения группы. message_id не входит —
// его назначает сервер при приёме. GKVersion 0 = публичная группа (plaintext).
type GroupMessageMeta struct {
	GroupID         string
	SenderID        string
	SenderDevice    string
	Kind            uint32
	CreatedAtUnixMs int64
	ThreadRoot      uint64
	ReplyTo         uint64
	GKVersion       uint32
}

// GroupMessageCanonicalBytes — preimage подписи сообщения группы: строго
// schema/proto/README.md, KAT-вектор group_message_canonical.
func GroupMessageCanonicalBytes(meta GroupMessageMeta, payload []byte) []byte {
	var out []byte
	out = appendLP(out, GroupMessageDomain)
	out = appendLP(out, meta.GroupID)
	out = appendLP(out, meta.SenderID)
	out = appendLP(out, meta.SenderDevice)
	out = appendU32(out, meta.Kind)
	out = appendU64(out, uint64(meta.CreatedAtUnixMs))
	out = appendU64(out, meta.ThreadRoot)
	out = appendU64(out, meta.ReplyTo)
	out = appendU32(out, meta.GKVersion)
	h := sha256.Sum256(payload)
	return append(out, h[:]...)
}

// VerifyEnvelopeSignature проверяет detached-подпись Ed25519 устройства отправителя
// над canonical_bytes (публичный ключ — из devices).
//
// Дополнительно отвергает неканоническую скалярную часть S (проверка low-S).
// Стандартная проверка Ed25519 её пропускает, и тогда из одной подписи без знания
// ключа получается ДРУГАЯ валидная подпись того же сообщения. Само по себе это
// не подделка, но всё, что где-либо считает байты подписи уникальными, начинает
// врать — поэтому проще не принимать такие подписи вовсе.
func VerifyEnvelopeSignature(senderSigningPub, canonicalBytes, signature []byte) bool {
	if len(senderSigningPub) != ed25519.PublicKeySize || len(signature) != ed25519.SignatureSize {
		return false
	}
	if !isCanonicalS(signature[32:]) {
		return false
	}
	return ed25519.Verify(ed25519.PublicKey(senderSigningPub), canonicalBytes, signature)
}

// order — порядок подгруппы Curve25519 (L), little-endian, как хранится S в подписи.
var order = [32]byte{
	0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
	0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
}

// isCanonicalS — S < L. Сравнение с конца (старшие байты) — S хранится little-endian.
func isCanonicalS(s []byte) bool {
	if len(s) != 32 {
		return false
	}
	for i := 31; i >= 0; i-- {
		if s[i] < order[i] {
			return true
		}
		if s[i] > order[i] {
			return false
		}
	}
	return false // S == L тоже неканонично
}

func appendU32(b []byte, v uint32) []byte { return binary.LittleEndian.AppendUint32(b, v) }
func appendU64(b []byte, v uint64) []byte { return binary.LittleEndian.AppendUint64(b, v) }

func appendLP(b []byte, s string) []byte {
	b = appendU32(b, uint32(len(s)))
	return append(b, s...)
}
