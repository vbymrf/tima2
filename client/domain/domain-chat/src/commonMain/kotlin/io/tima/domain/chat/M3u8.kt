package io.tima.domain.chat

/**
 * Что внутри m3u8 (ПЛАН-(ВМ) Р34–Р36) — решает устройство отправителя при вставке: есть видео —
 * виджет «Видео»; только звук и признак конца — запись, «Аудио»; конца нет — эфир, «Поток».
 */
object M3u8 {

    /** Разобранный список: главный (варианты) или с кусками. */
    data class Info(
        /** Главный список: варианты качества, кусков нет. */
        val master: Boolean,
        /** У варианта есть видео — кодек видео или разрешение. */
        val video: Boolean,
        /** Признак конца — запись, а не эфир. */
        val ended: Boolean,
        /** Сумма длин кусков, мс (у списка с кусками). */
        val durationMs: Long,
        /** Адрес первого варианта — у главного; по нему узнаётся, запись это или эфир. */
        val firstVariant: String?,
    )

    private val videoCodecs = listOf("avc1", "avc3", "hvc1", "hev1", "vp09", "vp8", "av01", "mp4v")

    fun parse(text: String, base: String): Info? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "#EXTM3U") return null
        var master = false
        var video = false
        var first: String? = null
        var duration = 0.0
        var waitUri = false
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-STREAM-INF") -> {
                    master = true
                    val codecs = Regex("CODECS=\"([^\"]*)\"").find(l)?.groupValues?.get(1).orEmpty()
                    if ("RESOLUTION=" in l || videoCodecs.any { it in codecs }) video = true
                    waitUri = first == null
                }
                l.startsWith("#EXTINF:") -> duration += l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                !l.startsWith("#") && waitUri -> {
                    first = resolve(base, l)
                    waitUri = false
                }
            }
        }
        val ended = lines.any { it == "#EXT-X-ENDLIST" } || lines.any { it.startsWith("#EXT-X-PLAYLIST-TYPE:VOD") }
        return Info(master, video, ended, (duration * 1000).toLong(), first)
    }

    /** Какой виджет у такого списка. Главному без видео нужен его [Info.firstVariant]. */
    fun widgetOf(info: Info, variant: Info? = null): MediaLinks.Kind = when {
        info.video -> MediaLinks.Kind.VIDEO_FILE
        info.master && variant != null -> if (variant.ended) MediaLinks.Kind.AUDIO_FILE else MediaLinks.Kind.STREAM
        info.master -> MediaLinks.Kind.STREAM
        info.ended -> MediaLinks.Kind.AUDIO_FILE
        else -> MediaLinks.Kind.STREAM
    }

    /** Относительный адрес варианта — от адреса списка. */
    fun resolve(base: String, ref: String): String = when {
        ref.startsWith("http://") || ref.startsWith("https://") -> ref
        ref.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + ref
        else -> base.substringBefore('?').substringBeforeLast('/') + "/" + ref
    }
}
