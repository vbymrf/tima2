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

    /**
     * Вариант качества главного списка: [quality] — меньшая сторона кадра («720p» и у вертикального
     * 720×1280), адрес и размер кадра — по нему окно знает, вертикальное ли видео (заказчик 2026-10-23, 3а).
     */
    data class Variant(val quality: Int, val url: String, val width: Int, val height: Int)

    /**
     * Качества главного списка для выбора в окне (заказчик 2026-10-23, 8а): по качеству, сверху лучшее;
     * одно качество — один вариант, с большей полосой. Без разрешения (только звук) — не качество.
     */
    fun variants(text: String, base: String): List<Variant> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.firstOrNull() != "#EXTM3U") return emptyList()
        val best = mutableMapOf<Int, Pair<Long, Variant>>()
        var pending: Triple<Int, Int, Long>? = null
        for (l in lines) {
            when {
                l.startsWith("#EXT-X-STREAM-INF") -> {
                    val size = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)?.groupValues
                    val bw = Regex("[^-]BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    pending = size?.let { Triple(it[1].toInt(), it[2].toInt(), bw) }
                }
                !l.startsWith("#") && pending != null -> {
                    val (w, h, bw) = pending!!
                    val q = minOf(w, h)
                    if ((best[q]?.first ?: -1) < bw) best[q] = bw to Variant(q, resolve(base, l), w, h)
                    pending = null
                }
                !l.startsWith("#") -> pending = null
            }
        }
        return best.entries.sortedByDescending { it.key }.map { it.value.second }
    }

    /** Относительный адрес варианта — от адреса списка. */
    fun resolve(base: String, ref: String): String = when {
        ref.startsWith("http://") || ref.startsWith("https://") -> ref
        ref.startsWith("/") -> base.substringBefore("://") + "://" + base.substringAfter("://").substringBefore('/') + ref
        else -> base.substringBefore('?').substringBeforeLast('/') + "/" + ref
    }
}
