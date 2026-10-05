@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package io.tima.crypto

import io.kodium.KodiumPrivateKey
import kotlin.io.encoding.Base64

/**
 * Доверие к устройствам (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ §2а, беда «вор SIM читает новые сообщения»).
 *
 * Цепочка: **ключ личности** (из фразы) заверяет **ключ подписи устройств** (КПУ) телефона, КПУ
 * — устройства аккаунта; устройство можно заверить и прямо ключом личности (ПК, вошедший по
 * фразе). Устройство, не заверенное этой цепочкой, — не устройство хозяина, и шифровать для него
 * нельзя: так в аккаунт попадал вор с перевыпущенной SIM.
 *
 * Байты — строка в строку как `server/internal/crypto/device_trust.go`: расхождение одной буквы
 * значит подпись, которую сервер не примет, без единого указания на причину.
 */
object DeviceTrust {

    const val BY_IDENTITY: String = "identity"
    const val BY_ASK: String = "ask"

    /** Что ключ личности подписывает, заверяя КПУ. */
    fun askCertBytes(askPub: ByteArray): ByteArray = ("tima.ask.v1|" + b64(askPub)).encodeToByteArray()

    /** Что КПУ или ключ личности подписывает, заверяя устройство. */
    fun deviceCertBytes(encryptionPub: ByteArray, signingPub: ByteArray): ByteArray =
        ("tima.device.v1|" + b64(encryptionPub) + "|" + b64(signingPub)).encodeToByteArray()

    /** Что ключ личности подписывает, отзывая КПУ (ДУ4). */
    fun askRevokeBytes(askId: String): ByteArray = "tima.ask-revoke.v1|$askId".encodeToByteArray()

    fun sign(key: KodiumPrivateKey, bytes: ByteArray): ByteArray? = MessageSigner.sign(key, bytes).getOrNull()

    /**
     * Заверено ли устройство цепочкой до [identityPub].
     *
     * @param signingKeys действующие КПУ аккаунта — как их отдал сервер; каждый проверяется
     *   здесь же, на слово серверу не верим.
     */
    fun deviceTrusted(
        identityPub: ByteArray?,
        signingKeys: List<SigningKeyCertificate>,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        certificate: DeviceCertificate?,
    ): Boolean {
        if (identityPub == null || identityPub.size != KEY || certificate == null) return false
        val bytes = deviceCertBytes(encryptionPub, signingPub)
        return when (certificate.by) {
            BY_IDENTITY -> verify(identityPub, bytes, certificate.signature)
            BY_ASK -> {
                val ask = signingKeys.firstOrNull { it.askId == certificate.askId } ?: return false
                askTrusted(identityPub, ask) && verify(ask.askPub, bytes, certificate.signature)
            }
            else -> false
        }
    }

    /** КПУ заверен ключом личности. */
    fun askTrusted(identityPub: ByteArray, ask: SigningKeyCertificate): Boolean =
        ask.askPub.size == KEY && verify(identityPub, askCertBytes(ask.askPub), ask.signature)

    private fun verify(pub: ByteArray, bytes: ByteArray, signature: ByteArray): Boolean =
        pub.size == KEY && signature.size == SIGNATURE && runCatching { MessageSigner.verify(pub, bytes, signature) }.getOrDefault(false)

    private fun b64(bytes: ByteArray): String = Base64.UrlSafe.encode(bytes).trimEnd('=')

    private const val KEY = 32
    private const val SIGNATURE = 64
}

/** КПУ аккаунта и его свидетельство ключом личности. */
class SigningKeyCertificate(val askId: String, val askPub: ByteArray, val signature: ByteArray)

/** Свидетельство устройства: кем подписано ([DeviceTrust.BY_IDENTITY] или [DeviceTrust.BY_ASK]). */
class DeviceCertificate(val by: String, val askId: String?, val signature: ByteArray)
