package io.tima.crypto

import io.kodium.Kodium
import io.kodium.core.nacl

/**
 * Слой 1 — конверт: `SecretBox(plaintext, message_key)` (crypto-protocol.md §3).
 *
 * Вывод/вход — `nonce(24) ‖ box` (формат Kodium). На вход подаются уже сжатые
 * и сериализованные байты (`zstd(protobuf(body))` — задача MessageSerializer, фаза 1.2);
 * этот слой шифрует произвольный ByteArray.
 */
object EnvelopeCipher {

    /** Шифрует payload одноразовым `message_key` (32 байта). Nonce генерируется CSPRNG. */
    fun seal(messageKey: ByteArray, plaintext: ByteArray): Result<ByteArray> =
        Kodium.encryptSymmetric(messageKey, plaintext)

    /** Открывает `nonce ‖ box`; провал MAC → failure. */
    fun open(messageKey: ByteArray, sealed: ByteArray): Result<ByteArray> =
        Kodium.decryptSymmetric(messageKey, sealed)

    /** Тест-хук KAT: тот же алгоритм с инъекцией фиксированного nonce (боевой код nonce не принимает). */
    internal fun sealWithNonce(messageKey: ByteArray, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        require(messageKey.size == nacl.SecretBox.KeySize) { "message_key должен быть ${nacl.SecretBox.KeySize} байта" }
        require(nonce.size == nacl.SecretBox.NonceSize) { "nonce должен быть ${nacl.SecretBox.NonceSize} байта" }
        return nonce + nacl.SecretBox.seal(plaintext, nonce, messageKey)
    }
}
