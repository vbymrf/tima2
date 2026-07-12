package io.tima.crypto

import io.kodium.Kodium
import io.kodium.KodiumPrivateKey

/**
 * Подпись и проверка конверта (crypto-protocol.md §7).
 *
 * Ed25519 detached над [CanonicalBytes.build]. Signing-ключ выводится Kodium из того же
 * 32-байтного seed, что и encryption-ключ устройства (одна `KodiumPrivateKey`).
 * Сервер проверяет подпись при приёме, клиенты — при получении.
 */
object MessageSigner {

    fun sign(deviceKey: KodiumPrivateKey, canonicalBytes: ByteArray): Result<ByteArray> =
        Kodium.signDetached(deviceKey, canonicalBytes)

    /** @param senderSigningPub Ed25519-публичный ключ устройства отправителя (32 байта, из `devices`) */
    fun verify(senderSigningPub: ByteArray, canonicalBytes: ByteArray, signature: ByteArray): Boolean =
        Kodium.verifyDetached(senderSigningPub, canonicalBytes, signature)
}
