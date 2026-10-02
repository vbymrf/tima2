package io.tima.core.network

/**
 * Код заверения уже подключённого устройства — `tima://certify/v1?device=…&enc=…&sig=…`
 * (ПЛАН-УСТРОЙСТВ-И-ИСТОРИИ Р32).
 *
 * Показывает незаверенное устройство, сканирует телефон с ключом подписи устройств. Код несёт
 * открытые ключи показывающего, и телефон сверяет их с тем, что лежит у сервера для этого
 * `device_id` в своём аккаунте: заверяется ровно то устройство, что стоит перед камерой.
 * Кнопка «Заверить» по строке списка этого не давала — список приходит от сервера, и по нему
 * можно было заверить и устройство вора с тем же именем.
 *
 * **Секрета в коде нет.** Ключи открытые; доказательство здесь — не содержимое, а то, что
 * экран показывающего оказался перед камерой хозяина.
 */
object CertifyQr {

    private const val PREFIX = "tima://certify/v1?"

    fun payload(deviceId: String, encryptionPub: ByteArray, signingPub: ByteArray): String =
        PREFIX + "device=" + deviceId + "&enc=" + encodeBase64Url(encryptionPub) + "&sig=" + encodeBase64Url(signingPub)

    fun isCertify(payload: String): Boolean = payload.trim().startsWith(PREFIX)

    /** @return `null` — не код заверения или испорчен. */
    fun parse(payload: String): CertifyCode? {
        val line = payload.trim()
        if (!line.startsWith(PREFIX)) return null
        val params = line.removePrefix(PREFIX).split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val device = params["device"]?.takeIf { it.isNotBlank() } ?: return null
        val enc = params["enc"]?.let { decodeBase64Url(it) }?.takeIf { it.size == KEY } ?: return null
        val sig = params["sig"]?.let { decodeBase64Url(it) }?.takeIf { it.size == KEY } ?: return null
        return CertifyCode(device, enc, sig)
    }

    private const val KEY = 32
}

/** Разобранный код заверения. */
class CertifyCode(val deviceId: String, val encryptionPub: ByteArray, val signingPub: ByteArray)
