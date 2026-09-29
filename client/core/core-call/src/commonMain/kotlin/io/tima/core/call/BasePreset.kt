package io.tima.core.call

/**
 * Потолок видео звонка — от сервера (ПЛАН-ВИДЕО.md В5б, решение заказчика 2026-09-29).
 *
 * Это граница, а не качество: ниже неё размером, частотой и битрейтом управляет WebRTC
 * сам. Сервер задаёт её, чтобы менять без выпуска приложения.
 */
data class VideoCeiling(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Бит/с. */
    val bitrate: Int,
) {
    companion object {
        /** Умолчание — те же числа, что у сервера без настройки: 1280×720, 24 к/с, 800 кбит/с. */
        val DEFAULT = VideoCeiling(width = 1280, height = 720, fps = 24, bitrate = 800_000)
    }
}

/**
 * Чем публикует **обычный звонок** — не прогон стенда (ПЛАН-ВИДЕО.md В3, В5б).
 *
 * ── ЧТО ЗДЕСЬ РЕШЕНО ────────────────────────────────────────────────────────
 *
 * - **Кодек — H.264, дальше VP8** ([CodecChoice]). VP9 и H.265 в обычном звонке выключены
 *   2026-09-29: VP9 у Redmi раскодируется с полосами, H.265 не показывает ПК. Не
 *   отвергнуты — стенд задаёт их и проверяет.
 * - **Запасного нет.** Запасной кодек LiveKit на стенде видео не спас (realme,
 *   2026-09-29), а «что отдали — то и объявили» (решение 2). VP8 — не запасной, а
 *   элемент выбора.
 * - **Потолок — от сервера** ([ceiling]); не прислал — [VideoCeiling.DEFAULT].
 * - **Чем жертвовать — по-прежнему «держать размер».** Решение 6 («решает WebRTC») ждёт
 *   сравнения прогонов с кратностью 16 (В2): без неё WebRTC ужимает до 360 и 270 по
 *   стороне, и кодер realme на этом полосит.
 */
fun basePreset(ceiling: VideoCeiling?): PublishPreset {
    val top = ceiling ?: VideoCeiling.DEFAULT
    return PublishPreset(
        name = BASE_PRESET,
        video = VideoPreset(
            codec = VideoCodec.H264,
            backup = null,
            width = top.width,
            height = top.height,
            fps = top.fps,
            bitrate = top.bitrate,
            maxBitrate = top.bitrate,
        ),
    )
}

/** Имя базового набора в журнале и отчётах. Латиницей не нужно: это текст, а не ключ. */
const val BASE_PRESET = "обычный звонок"
