package io.tima.core.call

/**
 * Какой кодек публиковать — **по тому, что телефон умеет кодировать, а не по пресету.**
 *
 * ── ЗАЧЕМ ────────────────────────────────────────────────────────────────
 *
 * Пресет просит H.264, а Honor 8S (Android 9, MT6761) его кодировать не умеет: WebRTC до
 * Android 10 берёт аппаратный H.264 только у Qualcomm и Exynos, программного H.264 в
 * сборке нет, H.265-кодера на телефоне нет вовсе. SDK на это не падает — он **молча**
 * согласует VP8, а сервер получает дорожку, объявленную как H.264, и выбрасывает её
 * (`could not find codec for webrtc receiver`). Итог: у одного видео есть, у другого нет,
 * и в журнале ни слова. Отчёт BXHS, 2026-09-25.
 *
 * Поэтому кодек выбирается ДО публикации, и объявляется ровно тот, что пойдёт по сети.
 *
 * ── ПОРЯДОК ─────────────────────────────────────────────────────────────
 *
 * Сначала просимый, потом H.264, потом VP9, потом H.265. VP9 кодирует libvpx внутри
 * WebRTC на любом телефоне, так что на нём перебор и кончается; H.265 последним — он
 * самый редкий у кодеров. Кодека, которого нет в наборе ([VideoCodec]), выбор не
 * предлагает: VP8 в набор не входит.
 *
 * Запасной кодек проходит тот же фильтр: неумеемый запасной сломал бы вторую дорожку
 * тем же способом, что и основной. Совпавший с основным — не запасной, он снимается.
 */
data class CodecChoice(
    val wanted: VideoCodec,
    val chosen: VideoCodec,
    val backup: VideoCodec?,
) {
    /** Пришлось ли отступить от пресета — повод для строки в журнале. */
    val substituted: Boolean get() = chosen != wanted

    companion object {
        private val FALLBACK = listOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.H265)

        /**
         * [encodable] — что умеет кодер телефона. Пустое множество значит «узнать не
         * удалось», и тогда пресет остаётся как есть: гадать хуже, чем не трогать.
         */
        fun pick(wanted: VideoCodec, backup: VideoCodec?, encodable: Set<VideoCodec>): CodecChoice {
            if (encodable.isEmpty()) return CodecChoice(wanted, wanted, backup)
            val chosen = (listOf(wanted) + FALLBACK).firstOrNull { it in encodable } ?: wanted
            val spare = backup?.takeIf { it in encodable && it != chosen }
            return CodecChoice(wanted, chosen, spare)
        }

        /** Имя кодека у WebRTC (`H264`, `VP9`, `H265`) в наш набор; чужое — `null`. */
        fun fromWebRtcName(name: String): VideoCodec? = when (name.uppercase()) {
            "H264" -> VideoCodec.H264
            "VP9" -> VideoCodec.VP9
            "H265", "HEVC" -> VideoCodec.H265
            else -> null
        }
    }
}
