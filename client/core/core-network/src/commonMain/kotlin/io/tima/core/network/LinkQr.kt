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

    private const val ПРЕФИКС = "tima://link/v1?"

    /**
     * @return `null` — код не наш или в нём нет обязательного. Различать «не тот QR» и
     *   «испорченный QR» человеку незачем: в обоих случаях надо показать код заново.
     */
    fun parse(payload: String): LinkQrData? {
        val строка = payload.trim()
        if (!строка.startsWith(ПРЕФИКС)) return null
        val хвост = строка.removePrefix(ПРЕФИКС)

        val поля = mutableMapOf<String, String>()
        for (кусок in хвост.split('&')) {
            val знак = кусок.indexOf('=')
            if (знак <= 0) continue
            поля[кусок.substring(0, знак)] = кусок.substring(знак + 1)
        }

        val sessionId = поля["session_id"]?.takeIf { it.isNotEmpty() } ?: return null
        val secret = поля["secret"]?.takeIf { it.isNotEmpty() } ?: return null
        val encryptionPub = поля["encryption_key"]?.let { decodeBase64Url(it) } ?: return null
        val signingPub = поля["signing_key"]?.let { decodeBase64Url(it) } ?: return null
        if (encryptionPub.size != КЛЮЧ || signingPub.size != КЛЮЧ) return null

        // Имя — единственное поле, которое можно не понять и продолжить: оно показывается
        // человеку («подключить Компьютер?»), а не участвует в подписи.
        val имя = поля["name"]?.let { decodeBase64Url(it) }?.decodeToString()

        return LinkQrData(sessionId, secret, encryptionPub, signingPub, имя)
    }

    private const val КЛЮЧ = 32
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
