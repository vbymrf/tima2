package io.tima.core.encryption

import io.tima.crypto.DeviceLinkSignature
import io.tima.crypto.MessageSigner
import io.tima.domain.account.LinkSigner

/**
 * Подпись привязки ключом этого устройства — переходник к порту домена.
 *
 * Байты подписи задаёт [DeviceLinkSignature]: домен-разделитель, session_id, secret и
 * ключи нового устройства. Раскладка **нормативна** — она зеркалит `linkSigningBytes` на
 * сервере байт-в-байт, и расхождение означает не «наша подпись другая», а «наша подпись
 * не проходит»: сервер откажет `bad_signature`.
 *
 * @param личность ключ этого устройства. `null` — подписывать нечем: устройство не
 *   заведено, и подтверждать привязку ему нельзя.
 */
class LinkSignerOverKodium(private val identity: DeviceIdentity?) : LinkSigner {

    override fun sign(
        sessionId: String,
        secret: String,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
    ): ByteArray? {
        val key = identity?.key ?: return null
        val bytes = DeviceLinkSignature.signingBytes(sessionId, secret, encryptionPub, signingPub)
        return MessageSigner.sign(key, bytes).getOrNull()
    }
}
