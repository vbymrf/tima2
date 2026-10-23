package io.tima.core.media

/**
 * Звук голосовых на устройстве (ПЛАН-(ВМ) ВМ2, ВМ4): микрофон, колонки и кодек Opus. Общая часть —
 * запись и проигрывание ([VoiceRecording], [VoicePlayback]) — говорит с платформой только через
 * эти три двери.
 */

/** Микрофон: моно, 16 бит, частота [rate]. */
interface PcmSource {
    val rate: Int

    /** Прочитать до [n] отсчётов в [buf] с [offset]; блокирует. Меньше нуля — микрофон отказал. */
    fun read(buf: ShortArray, offset: Int, n: Int): Int
    fun close()
}

/** Колонки: моно, 16 бит, частота [rate]. */
interface PcmSink {
    val rate: Int

    /** Записать [n] отсчётов; блокирует, пока не войдут в буфер. */
    fun write(buf: ShortArray, n: Int)

    /** Сбросить недоигранное (пауза, перемотка). */
    fun flush()
    fun close()
}

/** Кодер Opus: кадр [frame] отсчётов частоты [rate] → пакет. */
interface VoiceEncoder {
    val rate: Int
    val frame: Int

    /** Задержка кодера в отсчётах 48 кГц — `pre-skip` в заголовке Ogg Opus. */
    val preSkip: Int
    fun encode(pcm: ShortArray): ByteArray
}

/** Декодер Opus: пакет → отсчёты 48 кГц, моно. */
interface VoiceDecoder {
    /** Раскодировать пакет в [out] (не меньше 5760 отсчётов); вернуть, сколько вышло. */
    fun decode(packet: ByteArray, out: ShortArray): Int
}

expect object VoiceDevices {
    /**
     * Микрофон: [name] — выбранный для звонков (ПК, Р42), `null` — по умолчанию. Частота — 48 кГц,
     * не умеет — 16 кГц. Нет микрофона или нет разрешения — `null`.
     */
    fun microphone(name: String?): PcmSource?

    /** Колонки на 48 кГц; нет — `null`. */
    fun speaker(): PcmSink?

    /** Кодер Opus для голоса на частоте [rate], поток [VOICE_BITRATE]; нет кодека — `null`. */
    fun encoder(rate: Int): VoiceEncoder?

    fun decoder(): VoiceDecoder?
}

/** Поток голосового: около 180 КБ на минуту, 10 минут ≈ 1,8 МБ (ПЛАН-(ВМ) §2.1). */
const val VOICE_BITRATE = 24_000

/** Предел записи — 10 минут (Р1); на 9:30 — предупреждение. */
const val VOICE_MAX_MS = 10 * 60 * 1000L
const val VOICE_WARN_MS = VOICE_MAX_MS - 30_000L
