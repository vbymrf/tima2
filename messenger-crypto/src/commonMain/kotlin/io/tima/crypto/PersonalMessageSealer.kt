package io.tima.crypto

import io.kodium.Kodium
import io.kodium.KodiumPrivateKey

/** Адресат обёртки: устройство (или ВП) с identity-ключом X25519. */
data class DeviceAddress(
    val deviceId: String,
    val identityEncryptionPub: ByteArray,
)

/**
 * Собранный конверт — 1:1 зеркало `Envelope` из envelope.proto (protobuf-сериализация —
 * фаза 1.2, MessageSerializer). `wrappedKeys` сервер раскладывает в `personal_message_keys`.
 */
class SealedPersonalMessage(
    val formatVersion: Int,
    val meta: EnvelopeMeta,
    val encryptedPayload: ByteArray,
    val escrow: EscrowBlob,
    val senderEphemeralPub: ByteArray,
    val ratchetEnvelope: ByteArray,
    val signature: ByteArray,
    val wrappedKeys: Map<String, ByteArray>,
    /** Обязательство по ключу (ADR-0013); пусто в конвертах версии 1. */
    val keyCommitment: ByteArray = CanonicalBytes.EMPTY,
) {
    fun canonicalBytes(): ByteArray = CanonicalBytes.build(
        meta = meta,
        encryptedPayload = encryptedPayload,
        escrowBytes = escrow.canonicalConcat(),
        senderEphemeralPub = senderEphemeralPub,
        ratchetEnvelope = ratchetEnvelope,
        formatVersion = formatVersion,
        keyCommitment = keyCommitment,
    )
}

/**
 * Оркестрация слоёв личного сообщения (crypto-protocol.md §3.2–3.3).
 *
 * Слои: 1 — конверт (SecretBox), 2 — escrow (всегда), 4 — wrapped keys (всегда, план Б).
 * Слой 3 (ratchet, PFS) — фаза 5: здесь `ratchet_envelope` всегда пуст.
 *
 * На вход [seal] подаётся готовый payload (`zstd(protobuf(body))` — MessageSerializer, фаза 1.2).
 */
class PersonalMessageSealer(private val escrowModule: EscrowModule) {

    /**
     * @param recipientDevices устройства получателя И другие устройства отправителя
     *   (wrapped key для каждого — так работают мультиустройство и история)
     */
    fun seal(
        meta: EnvelopeMeta,
        payloadPlaintext: ByteArray,
        senderDeviceKey: KodiumPrivateKey,
        recipientDevices: List<DeviceAddress>,
    ): Result<SealedPersonalMessage> = runCatching {
        val messageKey = Kodium.generateHighEntropyKey()

        val encryptedPayload = EnvelopeCipher.seal(messageKey, payloadPlaintext).getOrThrow() // слой 1
        // Обязательство считаем ОДИН раз от того же ключа, которым шифровали: все
        // пути доставки обязаны привести получателя ровно к нему.
        val commitment = CanonicalBytes.keyCommitment(messageKey)
        val escrowBlob = escrowModule.wrap(messageKey).getOrThrow()                           // слой 2

        val senderEphemeral = Kodium.generateKeyPair()                                        // слой 4
        val wrappedKeys = recipientDevices.associate { device ->
            device.deviceId to WrappedKeyService.wrap(senderEphemeral, device.identityEncryptionPub, messageKey).getOrThrow()
        }

        val message = SealedPersonalMessage(
            formatVersion = CanonicalBytes.FORMAT_VERSION,
            meta = meta,
            encryptedPayload = encryptedPayload,
            escrow = escrowBlob,
            senderEphemeralPub = senderEphemeral.getPublicKey().encryptionKey,
            ratchetEnvelope = CanonicalBytes.EMPTY,
            signature = CanonicalBytes.EMPTY,
            wrappedKeys = wrappedKeys,
            keyCommitment = commitment,
        )
        val signature = MessageSigner.sign(senderDeviceKey, message.canonicalBytes()).getOrThrow()

        SealedPersonalMessage(
            formatVersion = message.formatVersion,
            meta = message.meta,
            encryptedPayload = message.encryptedPayload,
            escrow = message.escrow,
            senderEphemeralPub = message.senderEphemeralPub,
            ratchetEnvelope = message.ratchetEnvelope,
            signature = signature,
            wrappedKeys = wrappedKeys,
            keyCommitment = message.keyCommitment,
        )
    }

    companion object {
        /**
         * Путь B (crypto-protocol.md §3.3): проверка подписи → разворачивание wrapped_key →
         * открытие конверта. Возвращает payload (расжатие/десериализация — фаза 1.2).
         *
         * @param senderSigningPub Ed25519-ключ устройства отправителя (из `devices`);
         *   при провале подписи расшифровка не выполняется.
         */
        /**
         * @param wrapEphemeralOverride эфемерный публичный ключ обёртки, если он ОТЛИЧАЕТСЯ от
         *   `message.senderEphemeralPub` (восстановление истории, ADR-0010: помощник перезаворачивает
         *   `message_key` под новое устройство своим эфемералом). Подпись всё равно проверяется
         *   по оригинальному `senderEphemeralPub` (обёртки в подпись не входят). null — обычный путь.
         */
        fun openWithWrappedKey(
            message: SealedPersonalMessage,
            myDeviceId: String,
            myDeviceKey: KodiumPrivateKey,
            senderSigningPub: ByteArray,
            wrapEphemeralOverride: ByteArray? = null,
        ): Result<ByteArray> = runCatching {
            if (!MessageSigner.verify(senderSigningPub, message.canonicalBytes(), message.signature)) {
                throw VerificationFailure("Подпись конверта не прошла проверку")
            }
            val wrapped = message.wrappedKeys[myDeviceId]
                ?: throw IllegalStateException("Нет wrapped_key для устройства $myDeviceId")
            val wrapEphemeral = wrapEphemeralOverride ?: message.senderEphemeralPub
            val messageKey = WrappedKeyService.unwrap(myDeviceKey, wrapEphemeral, wrapped).getOrThrow()
            // Сверка обязательства (ADR-0013). Ключ добыт ОДНИМ из путей доставки,
            // и без этой проверки нечем убедиться, что остальные пути ведут к нему же.
            // Без неё отправитель мог бы сделать так, что по ордеру расшифруется
            // один текст, а здесь на экране — другой.
            if (message.formatVersion >= CanonicalBytes.FORMAT_VERSION &&
                !CanonicalBytes.commitmentMatches(messageKey, message.keyCommitment)
            ) {
                throw VerificationFailure("Обязательство по ключу не сошлось: пути доставки ведут к разным ключам")
            }
            EnvelopeCipher.open(messageKey, message.encryptedPayload).getOrThrow()
        }
    }
}
