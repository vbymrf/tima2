package io.tima.core.encryption

import io.kodium.Kodium
import io.kodium.KodiumPrivateKey
import io.tima.crypto.DeviceTrust
import io.tima.crypto.MessageSigner

/**
 * Ключ шифрования устройства на эпоху (ПЛАН-(ПС) ПС3, Р2, Р3): новая пара X25519 раз в эпоху
 * депозитария, открытая часть подписана ключом подписи устройства (он заверен КПУ или ключом
 * личности). Отправитель заворачивает под ключ эпохи, получатель держит прежние закрытые ключи
 * только на запас: утёкший потом ключ не откроет прошлое.
 */
object DeviceEpochKeys {

    /** Новый закрытый ключ эпохи — 32 байта секрета для хранилища платформы. */
    fun generate(): ByteArray = Kodium.generateKeyPair().exportToArray()

    /** Открытая часть ключа эпохи по его секрету. */
    fun publicOf(secret: ByteArray): ByteArray = KodiumPrivateKey.fromRaw(secret).getPublicKey().encryptionKey

    /** Подпись ключа эпохи ключом подписи этого устройства; `null` — не подписалось. */
    fun sign(identity: DeviceIdentity, deviceId: String, epoch: String, encryptionPub: ByteArray): ByteArray? =
        MessageSigner.sign(identity.key, DeviceTrust.deviceEpochKeyBytes(deviceId, epoch, encryptionPub)).getOrNull()

    /**
     * Ключ эпохи чужого устройства подписан его ключом подписи. [signingPub] — из того же списка,
     * что прошёл проверку заверения (ДУ3): так ключ эпохи наследует доверие устройства.
     */
    fun valid(signingPub: ByteArray, deviceId: String, epoch: String, encryptionPub: ByteArray, signature: ByteArray): Boolean =
        encryptionPub.size == 32 && runCatching {
            MessageSigner.verify(signingPub, DeviceTrust.deviceEpochKeyBytes(deviceId, epoch, encryptionPub), signature)
        }.getOrDefault(false)
}
