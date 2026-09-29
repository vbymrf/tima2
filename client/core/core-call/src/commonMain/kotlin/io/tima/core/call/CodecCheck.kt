package io.tima.core.call

/**
 * Уходит ли тот кодек, который просили (заказчик 2026-09-29).
 *
 * Строка журнала «кодек публикации шлём=…» пишет **просьбу**, а не то, что ушло. Дважды за
 * сентябрь они расходились молча: Honor просил H.264 и слал VP8 (кодер не умел), Samsung с
 * кратностью 16 просил VP9 и слал VP8 (фабрика кодеров не предлагала VP9). Оба раза в
 * журнале стояло «шлём=» просимое, и беду находили по косвенным признакам.
 *
 * Сверка — по кодеку исходящей дорожки из статистики WebRTC. Два опроса подряд, а не один:
 * при переопубликации камеры кодек на секунду бывает старым.
 */
class CodecCheck {

    /** Расхождение: просили [asked], уходит [sent]. */
    data class Mismatch(val asked: String, val sent: String)

    private var streak = 0
    private var last: Mismatch? = null

    /**
     * Очередной опрос. [asked] — просимый кодек, [backup] — запасной (он законно может
     * уходить), [sentMime] — `video/VP8` из статистики; `null` — видео не уходит, сверять
     * нечего. Возвращает расхождение, когда оно держится два опроса подряд.
     */
    fun next(asked: VideoCodec?, backup: VideoCodec?, sentMime: String?): Mismatch? {
        val sent = sentMime?.removePrefix("video/")?.uppercase()
        if (asked == null || sent.isNullOrBlank() || matches(asked, sent) || (backup != null && matches(backup, sent))) {
            streak = 0
            last = null
            return null
        }
        val now = Mismatch(asked.name, sent)
        streak = if (now == last) streak + 1 else 1
        last = now
        return now.takeIf { streak >= STREAK }
    }

    private fun matches(codec: VideoCodec, sent: String): Boolean =
        CodecChoice.fromWebRtcName(sent) == codec

    private companion object {
        const val STREAK = 2
    }
}
