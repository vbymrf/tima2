package io.tima.domain.chat

/**
 * Что за ссылка, если это видео или звук (ПЛАН-(ВМ) §3.3, §3.5): решается **по самой ссылке**, без
 * сети — домен или окончание файла. `null` — обычная ссылка, её открывает браузер (§4 Решений).
 */
object MediaLinks {

    enum class Kind {
        /** Прямой файл видео: mp4, mov, webm… */
        VIDEO_FILE,

        /** Прямой файл звука: mp3, m4a, ogg… */
        AUDIO_FILE,

        /** Поток HLS (`.m3u8`): запись, эфир или видео — узнаётся при вставке (Р34–Р36). */
        STREAM,
        YOUTUBE,
        RUTUBE,
        VK_VIDEO,
    }

    private val video = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp", "wmv", "ogv")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav")

    /** Играет ли ссылка в окне TIMA: прямые файлы, потоки и сайты (YouTube, VK, Rutube) — ВМ5–ВМ8. */
    fun playsHere(url: String): Boolean = kindOf(url) != null

    fun kindOf(url: String): Kind? {
        val u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return null
        val host = hostOf(u) ?: return null
        when {
            host == "youtu.be" || host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") ->
                return if (youtubeId(u) != null) Kind.YOUTUBE else null
            host.endsWith("rutube.ru") -> return if (rutubeId(u) != null) Kind.RUTUBE else null
            host == "vkvideo.ru" || host.endsWith(".vkvideo.ru") || host == "vk.com" || host.endsWith(".vk.com") ->
                return if (vkVideo(u) != null) Kind.VK_VIDEO else null
        }
        val ext = pathOf(u).substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return when (ext) {
            in video -> Kind.VIDEO_FILE
            in audio -> Kind.AUDIO_FILE
            "m3u8" -> Kind.STREAM
            else -> null
        }
    }

    /** Имя файла из адреса — раскодированное, без расширения (Р27: описание по имени файла). */
    fun fileName(url: String): String {
        val last = pathOf(url.trim()).trimEnd('/').substringAfterLast('/')
        return percentDecode(last).substringBeforeLast('.').ifBlank { hostOf(url).orEmpty() }
    }

    /** Номер ролика YouTube: `watch?v=`, `youtu.be/`, `shorts/`, `embed/`, `live/`. */
    fun youtubeId(url: String): String? {
        val host = hostOf(url) ?: return null
        val path = pathOf(url)
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            path.startsWith("/watch") -> query(url, "v")
            path.startsWith("/shorts/") || path.startsWith("/embed/") || path.startsWith("/live/") ->
                path.split('/').getOrNull(2)
            else -> null
        }
        return id?.takeIf { ID.matches(it) }
    }

    /** Номер ролика Rutube: `rutube.ru/video/<32 знака>/` или `/play/embed/<…>`. */
    fun rutubeId(url: String): String? {
        val parts = pathOf(url).split('/').filter { it.isNotEmpty() }
        val at = parts.indexOf("video").takeIf { it >= 0 } ?: parts.indexOf("embed").takeIf { it >= 0 } ?: return null
        return parts.getOrNull(at + 1)?.takeIf { RUTUBE.matches(it) }
    }

    /** Ролик VK: `video-65886767_456239913` в пути или `video_ext.php?oid=&id=`. Пара (oid, id). */
    fun vkVideo(url: String): Pair<String, String>? {
        val path = pathOf(url)
        VK.find(path)?.let { return it.groupValues[1] to it.groupValues[2] }
        if (path.endsWith("video_ext.php")) {
            val oid = query(url, "oid") ?: return null
            val id = query(url, "id") ?: return null
            return oid to id
        }
        return query(url, "z")?.let { VK.find(it) }?.let { it.groupValues[1] to it.groupValues[2] }
    }

    /** Ключ доступа ролика VK (`hash=` в адресе встраивания); нет — пусто: открытый ролик играет и так. */
    fun vkHash(url: String): String = query(url, "hash").orEmpty()

    /** Секунда из адреса: `t=90`, `t=1m30s`, `start=90` — начать с неё. */
    fun startSeconds(url: String): Int? {
        val t = query(url, "t") ?: query(url, "start") ?: return null
        T_PLAIN.matchEntire(t)?.let { return it.value.toInt() }
        val m = T_HMS.matchEntire(t) ?: return null
        val (h, min, s) = m.destructured
        return (h.toIntOrNull() ?: 0) * 3600 + (min.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }

    fun hostOf(url: String): String? {
        val rest = url.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .substringBefore(':').lowercase().removePrefix("www.").removePrefix("m.").takeIf { it.isNotEmpty() }
    }

    private fun pathOf(url: String): String =
        "/" + url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')

    private fun query(url: String, key: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=') == key }?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }

    private fun percentDecode(s: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes += v.toByte()
                    i += 3
                    continue
                }
            }
            if (c == '+') bytes += ' '.code.toByte() else c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private val ID = Regex("[A-Za-z0-9_-]{11}")
    private val RUTUBE = Regex("[0-9a-f]{32}")
    private val VK = Regex("video(-?\\d+)_(\\d+)")
    private val T_PLAIN = Regex("\\d+")
    private val T_HMS = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")
}
