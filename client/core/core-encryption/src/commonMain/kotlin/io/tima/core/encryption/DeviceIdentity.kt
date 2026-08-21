package io.tima.core.encryption

import io.kodium.Kodium
import io.kodium.KodiumPrivateKey

/**
 * Ключи устройства — единственная форма, в которой закрытый ключ существует за
 * пределами `messenger-crypto`.
 *
 * **Зачем обёртка.** Тип `KodiumPrivateKey` не должен ездить по слою Data: как
 * только он там появится, каждое место, работающее с сообщениями, начнёт знать имя
 * криптобиблиотеки — и её замена станет правкой всего слоя. Здесь он спрятан, а
 * наружу выходят только байты открытых ключей и сам этот handle.
 *
 * **Чего здесь НЕТ.** Хранения. Ключ надо где-то держать между запусками, и это
 * задача `core-secrets` (Keystore / Keychain / DPAPI) — модуля, которого пока нет.
 * Пока его нет, [exportRaw] и [fromRaw] — единственный мост, и вызывать их обязан
 * тот, кто отвечает за хранилище, а не этот модуль.
 */
class DeviceIdentity internal constructor(internal val key: KodiumPrivateKey) {

    /** Открытый ключ подписи (Ed25519, 32 байта) — его проверяет получатель. */
    val signingPublic: ByteArray get() = key.getPublicKey().signingKey

    /** Открытый ключ шифрования (X25519, 32 байта) — на него оборачивают ключи сообщений. */
    val encryptionPublic: ByteArray get() = key.getPublicKey().encryptionKey

    /**
     * Сырые байты закрытого ключа — для передачи в защищённое хранилище.
     *
     * Возвращаемый массив **секретен**: он не должен попадать ни в журнал, ни в
     * отчёт о проблеме, ни в кэш. Единственный законный получатель — хранилище
     * ключей платформы.
     */
    fun exportRaw(): ByteArray = key.exportToArray()

    companion object {
        /** Новое устройство. */
        fun generate(): DeviceIdentity = DeviceIdentity(Kodium.generateKeyPair())

        /**
         * Восстановление из сырых байт, полученных от хранилища.
         *
         * Идёт через `KodiumPrivateKey.fromRaw` — и это место, которое **нельзя
         * менять**: смена формата разбора здесь означает смену ключей у всех
         * устройств, то есть миграцию аккаунтов, а не правку кода
         * (`.cursor/rules/crypto-invariants.mdc`).
         */
        fun fromRaw(bytes: ByteArray): DeviceIdentity =
            DeviceIdentity(KodiumPrivateKey.fromRaw(bytes))
    }
}

/**
 * Адресат обёртки ключа: устройство получателя **или другое своё устройство**.
 *
 * Второе — не деталь реализации, а условие работы мультиустройства и истории: ключ
 * сообщения оборачивается на каждое устройство, включая собственные, иначе своё же
 * сообщение не откроется на планшете.
 */
data class RecipientDevice(
    val deviceId: String,
    /** Открытый ключ шифрования устройства, 32 байта. */
    val encryptionPublic: ByteArray,
) {
    // ByteArray в data class сравнивается по ссылке — переопределяем, иначе два
    // одинаковых адресата окажутся разными, и дедупликация списка не сработает.
    override fun equals(other: Any?): Boolean = other is RecipientDevice &&
        deviceId == other.deviceId && encryptionPublic.contentEquals(other.encryptionPublic)

    override fun hashCode(): Int = 31 * deviceId.hashCode() + encryptionPublic.contentHashCode()
}

/**
 * Открытый ключ escrow-эпохи: его отдаёт `GET /api/v1/escrow/key?chat_id=…`.
 *
 * Эпоха — «чат × месяц», и ключ у неё свой. Версия едет в конверте, потому что
 * расшифровать по ордеру можно только тем ключом, которым оборачивали.
 */
data class EscrowEpochKey(
    /** ML-KEM-768, 1184 байта. */
    val publicKey: ByteArray,
    val version: Int,
) {
    override fun equals(other: Any?): Boolean = other is EscrowEpochKey &&
        version == other.version && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = 31 * version + publicKey.contentHashCode()
}
