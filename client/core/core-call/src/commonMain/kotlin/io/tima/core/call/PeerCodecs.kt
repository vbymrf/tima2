package io.tima.core.call

/**
 * Аппаратное кодирование и раскодирование — переключатели «Настройки → Звонки»
 * (ПЛАН-ВИДЕО.md В4, решение заказчика 2026-09-29). По умолчанию включены.
 *
 * Зачем: аппаратный кодер или раскодировщик может портить картинку молча — полосы VP9 у
 * Redmi, H.264 у realme. WebRTC переходит на программный сам только при **ошибках**, а
 * полос он не видит. Переключатель — выход для человека, который их видит.
 */
data class HardwareCoding(val encode: Boolean = true, val decode: Boolean = true)

/**
 * Настройки с поправкой на прогон стенда: его выбор кодера и раскодировщика сильнее
 * переключателей (заказчик 2026-09-30, 1а). `null` — обычный звонок, одни настройки.
 */
fun HardwareCoding.forRun(video: VideoPreset?): HardwareCoding =
    if (video == null) this else HardwareCoding(video.encoder.pick(encode), video.decoder.pick(decode))

/** Ключи в настройках устройства — не синхронизируются: у каждого телефона свои кодеры. */
object HardwareCodingKeys {
    const val ENCODE = "call.hw.encode"
    const val DECODE = "call.hw.decode"

    fun read(all: Map<String, String>): HardwareCoding = HardwareCoding(
        encode = all[ENCODE] != "0",
        decode = all[DECODE] != "0",
    )
}

/**
 * Кто что раскодирует — сообщают друг другу участники звонка (ПЛАН-ВИДЕО.md В5, решение
 * заказчика 2026-09-29: «что отдали — то и объявили»).
 *
 * ── ПОЧЕМУ АТРИБУТОМ LIVEKIT ────────────────────────────────────────────────
 *
 * Сервер LiveKit знает, что участник заявил при соединении, а заявляет тот всё, что у него
 * есть, — в том числе раскодировщик, который полосит. Отправитель же должен выбрать кодек,
 * который **примут**. Атрибут участника идёт через наш же сервер LiveKit и не меняет ни
 * API TIMA, ни формат конверта (`Plan.md §0.0`, решение 5).
 */
object PeerCodecs {
    /** Имя атрибута. Латиницей: его читает другое устройство, а не человек. */
    const val ATTRIBUTE = "tima.decode"

    /** Сколько ждём атрибута вошедшего собеседника, прежде чем решить «не знаем». */
    const val WAIT_MS = 3_000L

    fun write(decodable: Set<VideoCodec>): String =
        decodable.sortedBy { it.ordinal }.joinToString(",") { it.wire }

    /** `null` — атрибута нет: старая сборка или ещё не пришёл. */
    fun read(value: String?): Set<VideoCodec>? {
        if (value.isNullOrBlank()) return null
        return value.split(',').mapNotNull { part ->
            VideoCodec.entries.firstOrNull { it.wire.equals(part.trim(), ignoreCase = true) }
        }.toSet()
    }

    /**
     * Какой кодек публиковать, если собеседники сказали, что примут.
     *
     * @param wanted просимый (у обычного звонка — H.264).
     * @param encodable что умеет кодер этого устройства; пусто — «не узнали», и тогда
     *   решает только собеседник.
     * @param peers что раскодирует каждый собеседник в комнате; `null` — не знаем
     *   (решение 4: «не знаем — значит VP8», его программно раскодируют все).
     *
     * Порядок — просимый, H.264, VP8 ([CodecChoice]). Никого в комнате — ограничения нет:
     * выбор пересмотрят, когда собеседник войдёт.
     */
    fun choose(wanted: VideoCodec, encodable: Set<VideoCodec>, peers: List<Set<VideoCodec>?>): VideoCodec {
        val order = (listOf(wanted) + CodecChoice.ORDER).distinct()
        fun everyoneTakes(codec: VideoCodec) = peers.all { it?.contains(codec) ?: (codec == VideoCodec.VP8) }
        fun weEncode(codec: VideoCodec) = encodable.isEmpty() || codec in encodable
        return order.firstOrNull { weEncode(it) && everyoneTakes(it) } ?: VideoCodec.VP8
    }
}
