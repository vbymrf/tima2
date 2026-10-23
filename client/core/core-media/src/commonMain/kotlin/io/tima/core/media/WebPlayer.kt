package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Браузерный движок окна проигрывания (ПЛАН-(ВМ) ВМ6): проигрыватель сайта (YouTube, VK, Rutube) или
 * `<video>` для прямого файла — в `WebView` на Android и в WebView2 на ПК. Своя страница (`WebPages`)
 * держит мост: отдаёт время, состояние и ошибки, принимает пуск, паузу и перемотку.
 *
 * YouTube на Android — android-youtube-player (Р14): та же страница проигрывателя YouTube, но с
 * правильным адресом страницы и готовым мостом.
 */
interface WebPlayer {
    val state: StateFlow<OwnPlayerState>
    fun open(page: WebPage, startMs: Long = 0)
    fun play()
    fun pause()
    fun seek(ms: Long)

    /**
     * Звук выключен или включён (заказчик 2026-10-23). VK запускает встроенное видео без звука —
     * окно включает его, когда видео пошло. Страница ещё не готова — запомнит и включит сама.
     */
    fun setMuted(muted: Boolean)
    fun release()
}

/** Что открыть браузерным движком: вид, адрес и готовая страница с мостом. */
data class WebPage(
    val kind: Kind,
    val url: String,
    /** Номер ролика YouTube — для android-youtube-player. */
    val videoId: String = "",
    val html: String = "",
    /** Сайт проигрывателя и номер нашего ролика в его адресах — узнать уход на другое видео (4а). */
    val sites: List<String> = emptyList(),
    val key: String = "",
) {
    enum class Kind { FILE, YOUTUBE, RUTUBE, VK }

    /**
     * Страница [url] внутри проигрывателя — уже не наш ролик: адрес того же сайта без номера нашего.
     * Чужие сайты (реклама, счётчики) не в счёт; движок зовёт это только сразу после касания, чтобы
     * служебные страницы сайта, открытые им самим, не зажигали «Перезапустить».
     */
    fun isOther(url: String): Boolean {
        if (key.isEmpty()) return false
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        return sites.any { host == it || host.endsWith(".$it") } && key !in url
    }

    companion object {
        /** Адрес нашей страницы: у YouTube и VK проигрыватель хочет настоящий адрес https, а не `about:blank`. */
        const val ORIGIN = "https://player.tima.local/"
    }
}

/** Браузерный движок этой платформы; `null` — не умеет (iOS, ПК без WebView2). */
@Composable
expect fun rememberWebPlayer(): WebPlayer?

/** Место браузерного движка в окне. */
@Composable
expect fun WebPlayerSurface(player: WebPlayer, modifier: Modifier)

/**
 * Сообщение моста страницы → состояние окна: `{"t":"time","v":12.3,"d":600}`, `{"t":"state","v":"playing"}`,
 * `{"t":"error","v":"youtube 150"}`, `{"t":"ready"}`, `{"t":"away"}` — проигрыватель ушёл с нашего ролика,
 * `{"t":"size","w":720,"h":1280}` — размер кадра файла.
 * Незнакомое — без изменений.
 */
fun OwnPlayerState.bridged(message: String): OwnPlayerState {
    fun field(name: String): String? =
        Regex(""""$name"\s*:\s*("((?:[^"\\]|\\.)*)"|[-0-9.eE]+)""").find(message)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } }
    return when (field("t")) {
        "time" -> {
            val at = ((field("v")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
            val d = ((field("d")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
            copy(positionMs = at, durationMs = if (d > 0) d else durationMs, started = started || at > 300)
        }
        "state" -> when (field("v")) {
            "playing" -> copy(playing = true, buffering = false, ended = false)
            "paused" -> copy(playing = false, buffering = false)
            "buffering" -> copy(buffering = true)
            "ended" -> copy(playing = false, ended = true)
            else -> this
        }
        "error" -> copy(error = field("v") ?: "error", playing = false)
        "away" -> copy(elsewhere = true)
        "size" -> copy(width = field("w")?.toDoubleOrNull()?.toInt() ?: width, height = field("h")?.toDoubleOrNull()?.toInt() ?: height)
        else -> this
    }
}
