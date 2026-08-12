package escrow

// Подпись конфига анклава (ПЛАН-РЕФАКТОРИНГА.md Р2; doc/adr/0012-escrow-key-epochs.md
// «Что осталось»). Без неё компрометация бэкенда позволяет подменить public_key,
// который клиент получает через /api/v1/escrow/*: клиент зашифровал бы сообщение
// на чужой ключ в обход анклава и всего механизма контролируемого доступа
// (порог долей, аудит, срок уничтожения). Анклав подписывает ответ приватным
// ключом Ed25519, который живёт только здесь; бэкенд пересылает подпись, не
// имея возможности её подделать (server/internal/api/escrow.go). Клиент
// проверяет заранее известным публичным ключом анклава — без него подпись
// ничего не доказывает, см. обсуждение выбора в ПЛАН-РЕФАКТОРИНГА.md Р2.
//
// Раскладка байт здесь нормативна: Kotlin-реализация обязана воспроизводить её
// байт-в-байт (schema/proto/README.md §escrow_key_signing_bytes), иначе подпись,
// сделанная сервером, не пройдёт проверку у клиента и наоборот.

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/binary"
)

// KeyMetaSigningBytes — подписываемые байты KeyMeta (/v1/key, /v1/keys/{id}).
//
// Времена — unix-миллисекунды, little-endian: подпись не должна зависеть от
// того, как time.Time сериализуется в JSON (RFC3339 с произвольной точностью
// секунд легко разъезжается между реализациями на доли секунды).
func KeyMetaSigningBytes(m KeyMeta) ([]byte, error) {
	pub, err := base64.RawURLEncoding.DecodeString(m.PublicKey)
	if err != nil {
		return nil, err
	}
	var out []byte
	out = appendU32(out, m.ID)
	out = appendLP(out, m.Region)
	out = appendLP(out, m.Epoch)
	out = appendLP(out, m.ChatID)
	out = append(out, pub...)
	out = appendU64(out, uint64(m.ValidFrom.UnixMilli()))
	out = appendU64(out, uint64(m.ValidTo.UnixMilli()))
	out = appendU64(out, uint64(m.DestroyAt.UnixMilli()))
	return out, nil
}

// PubkeySigningBytes — подписываемые байты легаси-ответа GET /v1/pubkey.
func PubkeySigningBytes(version int, pubKey []byte) []byte {
	var out []byte
	out = appendU32(out, uint32(version))
	out = append(out, pubKey...)
	return out
}

// sign — подпись Ed25519 поверх уже собранных канонических байт.
func (e *Enclave) sign(msg []byte) string {
	return base64.RawURLEncoding.EncodeToString(ed25519.Sign(e.signingPriv, msg))
}

func appendU32(b []byte, v uint32) []byte { return binary.LittleEndian.AppendUint32(b, v) }
func appendU64(b []byte, v uint64) []byte { return binary.LittleEndian.AppendUint64(b, v) }

func appendLP(b []byte, s string) []byte {
	b = appendU32(b, uint32(len(s)))
	return append(b, s...)
}
