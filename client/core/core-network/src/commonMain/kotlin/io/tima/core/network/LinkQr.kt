package io.tima.core.network

/**
 * Код привязки: `tima://link/v1?session_id=…&secret=…&encryption_key=…&signing_key=…&name=…`
 *
 * Формат **нормативный** — его составляет сервер (`device_link.go`), и подтверждающее
 * устройство подписывает разобранные значения. Расхождение разбора не остаётся тихим:
 * подпись пойдёт не по тем байтам, и сервер откажет `bad_signature`. Это не повод
 * разбирать небрежно, а причина, по которой небрежность здесь дорога — отказ выглядит как
 * поломка подписи, а не как поломка разбора.
 *
 * Своя разборка, а не URL-парсер: значения короткие и уже в base64url, то есть без
 * процентного кодирования, а тащить в общий код разборщик URL ради пяти параметров —
 * зависимость дороже задачи.
 */
object LinkQr {

    private const val PREFIX = "tima://link/v1?"

    /**
     * @return `null` — код не наш или в нём нет обязательного. Различать «не тот QR» и
     *   «испорченный QR» человеку незачем: в обоих случаях надо показать код заново.
     */
    fun parse(payload: String): LinkQrData? {
        val line = payload.trim()
        if (!line.startsWith(PREFIX)) return null
        val tail = line.removePrefix(PREFIX)

        val fields = mutableMapOf<String, String>()
        for (chunk in tail.split('&')) {
            val glyph = chunk.indexOf('=')
            if (glyph <= 0) continue
            fields[chunk.substring(0, glyph)] = chunk.substring(glyph + 1)
        }

        val sessionId = fields["session_id"]?.takeIf { it.isNotEmpty() } ?: return null
        val secret = fields["secret"]?.takeIf { it.isNotEmpty() } ?: return null
        val encryptionPub = fields["encryption_key"]?.let { decodeBase64Url(it) } ?: return null
        val signingPub = fields["signing_key"]?.let { decodeBase64Url(it) } ?: return null
        if (encryptionPub.size != KEY || signingPub.size != KEY) return null

        // Имя — единственное поле, которое можно не понять и продолжить: оно показывается
        // человеку («подключить Компьютер?»), а не участвует в подписи.
        val name = fields["name"]?.let { decodeBase64Url(it) }?.decodeToString()

        return LinkQrData(sessionId, secret, encryptionPub, signingPub, name)
    }

    private const val KEY = 32
}

/**
 * Разобранный код привязки.
 *
 * Ключи здесь — **нового** устройства, а подпись делается ими же: сервер сверит их с тем,
 * что сам положил в сессию.
 */
class LinkQrData(
    val sessionId: String,
    /** Секрет из кода: он доказывает, что подтверждающий видел именно этот код. */
    val secret: String,
    val encryptionPub: ByteArray,
    val signingPub: ByteArray,
    /** Как новое устройство себя назвало. Показывается человеку перед подтверждением. */
    val deviceName: String?,
)
