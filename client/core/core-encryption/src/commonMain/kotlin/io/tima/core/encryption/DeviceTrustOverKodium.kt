package io.tima.core.encryption

import io.kodium.Kodium
import io.kodium.KodiumPrivateKey
import io.tima.crypto.AccountMnemonic
import io.tima.crypto.DeviceTrust
import io.tima.domain.account.DeviceProof
import io.tima.domain.account.DeviceProver

/**
 * Ключ подписи устройств (КПУ) телефона — ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ1.
 *
 * Свой у каждого телефона; закрытая часть рождается здесь и уходит только в хранилище
 * платформы. Ключ личности (фраза) заверяет КПУ, КПУ заверяет устройства аккаунта — так
 * собеседники отличают устройство хозяина от устройства вора с перевыпущенной SIM.
 *
 * Как и [DeviceIdentity], восстанавливается через `KodiumPrivateKey.fromRaw` — формат не
 * меняется (`crypto-invariants.mdc`).
 */
class AccountSigningKey internal constructor(internal val key: KodiumPrivateKey) {

    /** Открытый ключ подписи (Ed25519, 32 байта). */
    val public: ByteArray get() = key.getPublicKey().signingKey

    /** Сырые байты закрытого ключа — только для хранилища платформы. */
    fun exportRaw(): ByteArray = key.exportToArray()

    /** Свидетельство устройства этим КПУ. */
    fun certify(encryptionPub: ByteArray, signingPub: ByteArray): ByteArray? =
        DeviceTrust.sign(key, DeviceTrust.deviceCertBytes(encryptionPub, signingPub))

    companion object {
        fun generate(): AccountSigningKey = AccountSigningKey(Kodium.generateKeyPair())
        fun fromRaw(bytes: ByteArray): AccountSigningKey = AccountSigningKey(KodiumPrivateKey.fromRaw(bytes))
    }
}

/**
 * Подписи ключом личности для доверия к устройствам. Слова приходят, превращаются в подпись и
 * уходят — держать их негде и незачем (как [IdentitySignerOverKodium]).
 */
object IdentityTrustSigner {

    private fun identity(words: List<String>): KodiumPrivateKey? =
        runCatching { AccountMnemonic.identityFromMnemonic(words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }) }.getOrNull()

    /** Свидетельство КПУ ключом личности; `null` — фраза не та. */
    fun certifyAsk(words: List<String>, askPub: ByteArray): ByteArray? =
        identity(words)?.let { DeviceTrust.sign(it, DeviceTrust.askCertBytes(askPub)) }

    /** Свидетельство устройства прямо ключом личности (ПК по фразе, «заверить фразой»). */
    fun certifyDevice(words: List<String>, encryptionPub: ByteArray, signingPub: ByteArray): ByteArray? =
        identity(words)?.let { DeviceTrust.sign(it, DeviceTrust.deviceCertBytes(encryptionPub, signingPub)) }
}

/**
 * Доказательство устройства при заведении (ДУ2) — переходник к порту домена.
 *
 * Телефон заводит свой КПУ, заверяет его фразой и им — себя. ПК КПУ не держит (Р12) и
 * заверяет себя прямо фразой.
 */
object DeviceProverOverKodium : DeviceProver {

    override fun prove(words: List<String>, encryptionPub: ByteArray, signingPub: ByteArray, phone: Boolean): DeviceProof? {
        if (!phone) {
            val cert = IdentityTrustSigner.certifyDevice(words, encryptionPub, signingPub) ?: return null
            return DeviceProof(askPub = null, askSig = null, askSecret = null, certBy = DeviceTrust.BY_IDENTITY, certSig = cert)
        }
        val ask = AccountSigningKey.generate()
        val askSig = IdentityTrustSigner.certifyAsk(words, ask.public) ?: return null
        val cert = ask.certify(encryptionPub, signingPub) ?: return null
        return DeviceProof(
            askPub = ask.public,
            askSig = askSig,
            askSecret = ask.exportRaw(),
            certBy = DeviceTrust.BY_ASK,
            certSig = cert,
        )
    }
}

/**
 * Проверка цепочки доверия для слоёв выше — фасад над `io.tima.crypto.DeviceTrust`: выше
 * `core-encryption` крипто-типы не ходят (правило архитектуры), поэтому здесь только байты и
 * строки.
 */
object DeviceTrustCheck {

    const val BY_IDENTITY: String = DeviceTrust.BY_IDENTITY
    const val BY_ASK: String = DeviceTrust.BY_ASK

    /** Что подписывают, заверяя устройство: ключ аттестации подписывает то же самое (ДУ8). */
    fun deviceCertBytes(encryptionPub: ByteArray, signingPub: ByteArray): ByteArray =
        DeviceTrust.deviceCertBytes(encryptionPub, signingPub)

    /** Ключ подписи устройств аккаунта со свидетельством — как его отдал сервер. */
    class SigningKey(val askId: String, val askPub: ByteArray, val askSig: ByteArray)

    /** Заверено ли устройство цепочкой до [identityPub]. */
    fun trusted(
        identityPub: ByteArray?,
        signingKeys: List<SigningKey>,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        certBy: String?,
        certAskId: String?,
        certSig: ByteArray?,
    ): Boolean = DeviceTrust.deviceTrusted(
        identityPub = identityPub,
        signingKeys = signingKeys.map { io.tima.crypto.SigningKeyCertificate(it.askId, it.askPub, it.askSig) },
        encryptionPub = encryptionPub,
        signingPub = signingPub,
        certificate = if (certBy != null && certSig != null) io.tima.crypto.DeviceCertificate(certBy, certAskId, certSig) else null,
    )
}
