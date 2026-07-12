package io.tima.crypto

import io.kodium.KodiumPrivateKey
import io.kodium.core.nacl

/**
 * Слой 4 — обёртки ключа сообщения (план Б, путь B; crypto-protocol.md §3).
 *
 * `wrapped_key[device] = Box(sender_ephemeral_priv, device_identity_pub, message_key)`,
 * формат — `nonce(24) ‖ box` (как у `Kodium.encrypt`). Обёртки создаются на каждое
 * устройство получателя И отправителя (мультиустройство, история на новом устройстве).
 * В подпись конверта не входят: подмена wrapped_key даёт провал расшифровки
 * (MAC SecretBox подписанного payload), а не поддельный текст.
 */
object WrappedKeyService {

    /** Оборачивает `message_key` для устройства. Nonce генерируется CSPRNG. */
    fun wrap(senderEphemeral: KodiumPrivateKey, deviceIdentityPub: ByteArray, messageKey: ByteArray): Result<ByteArray> =
        runCatching {
            wrapWithNonce(senderEphemeral.secretKey, deviceIdentityPub, messageKey, nacl.randomBytes(nacl.Box.NonceSize))
        }

    /** Разворачивает `message_key` приватным ключом устройства и эфемерным публичным отправителя. */
    fun unwrap(deviceKey: KodiumPrivateKey, senderEphemeralPub: ByteArray, wrapped: ByteArray): Result<ByteArray> {
        val minSize = nacl.Box.NonceSize + nacl.Box.MacSize
        if (wrapped.size < minSize) {
            return Result.failure(IllegalArgumentException("wrapped_key короче минимума в $minSize байт"))
        }
        val nonce = wrapped.copyOfRange(0, nacl.Box.NonceSize)
        val box = wrapped.copyOfRange(nacl.Box.NonceSize, wrapped.size)
        val key = nacl.Box.open(box, nonce, senderEphemeralPub, deviceKey.secretKey)
        return if (key == null || key.isEmpty()) {
            Result.failure(IllegalStateException("Не удалось развернуть wrapped_key (MAC/ключи)"))
        } else {
            Result.success(key)
        }
    }

    /** Тест-хук KAT: обёртка с инъекцией фиксированного nonce и сырых ключей. */
    internal fun wrapWithNonce(
        ephemeralSecret: ByteArray,
        recipientPub: ByteArray,
        messageKey: ByteArray,
        nonce: ByteArray,
    ): ByteArray {
        require(nonce.size == nacl.Box.NonceSize) { "nonce должен быть ${nacl.Box.NonceSize} байта" }
        return nonce + nacl.Box.seal(messageKey, nonce, recipientPub, ephemeralSecret)
    }
}
