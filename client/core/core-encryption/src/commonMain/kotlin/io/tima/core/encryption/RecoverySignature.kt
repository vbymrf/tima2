package io.tima.core.encryption

import io.tima.crypto.AccountMnemonic
import io.tima.crypto.MessageSigner

/**
 * Подпись запроса на восстановление ключей — ключом личности, а не устройства.
 *
 * **Зачем вообще подпись.** Укравший SIM получает код по SMS, а с ним и доступ
 * устройства. Историю группы это ему не откроет: сервер требует подпись ключом личности,
 * который выводится из двенадцати слов и на устройстве не хранится (ADR-0010 §этап 3).
 * Поэтому запрос истории — единственное место, где фразу спрашивают не при входе.
 *
 * **Слова не сохраняются.** Они приходят сюда, превращаются в подпись и уходят: держать
 * их в состоянии экрана или в хранилище значило бы свести на нет весь заслон — тогда
 * укравшему устройство доставалась бы и фраза.
 */
object RecoverySignature {

    /** `tima.recover.v1|<group_id>|<requester_device>` — то же, что считает сервер. */
    fun canonicalBytes(groupId: String, deviceId: String): ByteArray =
        "tima.recover.v1|$groupId|$deviceId".encodeToByteArray()

    /**
     * @return подпись base64url либо `null`, если слова не складываются в личность —
     *   опечатка в фразе выглядит именно так, и это не поломка.
     */
    fun sign(words: List<String>, groupId: String, deviceId: String): String? {
        val ключ = runCatching { AccountMnemonic.identityFromMnemonic(words) }.getOrNull() ?: return null
        val подпись = MessageSigner.sign(ключ, canonicalBytes(groupId, deviceId)).getOrNull() ?: return null
        return encodeBase64UrlBytes(подпись)
    }
}

/** Base64url без выравнивания — как ждёт сервер. */
private fun encodeBase64UrlBytes(bytes: ByteArray): String {
    val алфавит = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val sb = StringBuilder((bytes.size * 4 + 2) / 3)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = (bytes[i].toInt() and 0xFF shl 16) or
            (bytes[i + 1].toInt() and 0xFF shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        sb.append(алфавит[n ushr 18 and 63]).append(алфавит[n ushr 12 and 63])
            .append(алфавит[n ushr 6 and 63]).append(алфавит[n and 63])
        i += 3
    }
    when (bytes.size - i) {
        1 -> {
            val n = bytes[i].toInt() and 0xFF shl 16
            sb.append(алфавит[n ushr 18 and 63]).append(алфавит[n ushr 12 and 63])
        }
        2 -> {
            val n = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
            sb.append(алфавит[n ushr 18 and 63]).append(алфавит[n ushr 12 and 63])
                .append(алфавит[n ushr 6 and 63])
        }
    }
    return sb.toString()
}
