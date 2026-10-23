package io.tima.feature.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.MediaWindow
import io.tima.core.media.OwnPlayerSurface
import io.tima.core.media.rememberOwnPlayer
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Tima
import io.tima.core.ui.words
import io.tima.domain.chat.MediaLinks
import kotlinx.coroutines.delay

/** Что открыть в окне проигрывания (ПЛАН-(ВМ) §3.1): адрес, название, с какой секунды. */
data class PlayRequest(
    val url: String,
    val title: String = "",
    val startMs: Long = 0,
    /** Метки этого видео (§3.6): список в окне, отрезки на полосе, текущая подсвечена. */
    val marks: List<io.tima.domain.chat.MediaMark> = emptyList(),
)

/** Что случилось в окне — для журнала (`PLAYER`); слова — у пишущего журнал. */
enum class PlayerEvent {
    OPENED,
    STARTED,

    /** Движок сказал «не могу». */
    FAILED,

    /** Сторож: за [PLAYER_WATCH_MS] ни кадра, ни звука. */
    TIMEOUT,

    /** Сменили движок — сами после отказа или кнопкой «Другой проигрыватель». */
    SWITCHED,
    TO_BROWSER,
}

/** Сколько ждать первого кадра или звука, прежде чем считать, что движок не смог (§3.4). */
const val PLAYER_WATCH_MS = 15_000L

/** Движок окна (§3.2): свой (Media3, Media Foundation) или браузерный (WebView, WebView2). */
enum class PlayerEngine { OWN, WEB }

/**
 * Что окну нужно снаружи (§3.3, §3.4): поток сайта для своего движка и память по сайту — с каким
 * движком видео пошло в прошлый раз.
 */
class PlayerPorts(
    /** Поток HLS Rutube или VK для своего движка; не вышло — `null`. */
    val resolve: suspend (kind: MediaLinks.Kind, url: String) -> String? = { _, _ -> null },
    val remembered: (host: String) -> PlayerEngine? = { null },
    val remember: (host: String, engine: PlayerEngine) -> Unit = { _, _ -> },
)

/**
 * Порядок движков по виду ссылки (§3.3): прямой файл и поток — свой, потом браузерный; Rutube —
 * свой по их запросу, потом браузерный; VK — браузерный, потом свой (свой хрупок: проверка на робота);
 * YouTube — только браузерный (Р14). С каким пошло в прошлый раз на этом сайте — тот первым.
 */
fun engineOrder(kind: MediaLinks.Kind?, remembered: PlayerEngine?): List<PlayerEngine> {
    val base = when (kind) {
        MediaLinks.Kind.YOUTUBE -> listOf(PlayerEngine.WEB)
        MediaLinks.Kind.VK_VIDEO -> listOf(PlayerEngine.WEB, PlayerEngine.OWN)
        else -> listOf(PlayerEngine.OWN, PlayerEngine.WEB)
    }
    return if (remembered != null && remembered in base) listOf(remembered) + (base - remembered) else base
}

/** Страница браузерного движка для ссылки (ВМ6). */
fun webPageOf(url: String, startSec: Int): io.tima.core.media.WebPage? = when (MediaLinks.kindOf(url)) {
    MediaLinks.Kind.YOUTUBE -> MediaLinks.youtubeId(url)?.let { io.tima.core.media.WebPages.youtube(url, it, startSec) }
    MediaLinks.Kind.RUTUBE -> MediaLinks.rutubeId(url)?.let { io.tima.core.media.WebPages.rutube(url, it, startSec) }
    MediaLinks.Kind.VK_VIDEO -> MediaLinks.vkVideo(url)?.let { (oid, id) -> io.tima.core.media.WebPages.vk(url, oid, id, MediaLinks.vkHash(url), startSec) }
    MediaLinks.Kind.VIDEO_FILE, MediaLinks.Kind.AUDIO_FILE, MediaLinks.Kind.STREAM -> io.tima.core.media.WebPages.file(url, startSec)
    null -> null
}

object PlayerTags {
    const val WINDOW = "player:window"
    const val PLAY = "player:play"
    const val BAR = "player:bar"
    const val SPEED = "player:speed"
    const val BROWSER = "player:browser"
    const val OTHER = "player:other"
    const val CLOSE = "player:close"
}

/**
 * Окно проигрывания (ПЛАН-(ВМ) §3.1–§3.4): видео и звук по ссылке — двумя движками. Не смог
 * (ошибка или сторож 15 с) — окно само берёт другой движок с той же секунды; «Другой проигрыватель» —
 * то же руками; ни один не смог — «Открыть в браузере». Движок, с которым пошло, запоминается по сайту.
 */
@Composable
fun PlayerWindow(
    request: PlayRequest,
    onClose: () -> Unit,
    onBrowser: (String) -> Unit,
    onEvent: (what: PlayerEvent, detail: String) -> Unit = { _, _ -> },
    ports: PlayerPorts = PlayerPorts(),
) {
    val title = request.title.ifBlank { MediaLinks.hostOf(request.url).orEmpty() }
    MediaWindow(title = title, onClose = onClose) {
        PlayerContent(request, title, onClose, onBrowser, onEvent, ports)
    }
}

@Composable
private fun PlayerContent(
    request: PlayRequest,
    title: String,
    onClose: () -> Unit,
    onBrowser: (String) -> Unit,
    onEvent: (PlayerEvent, String) -> Unit,
    ports: PlayerPorts,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    val onVideo = io.tima.core.ui.TimaFixed.onVideo
    val host = MediaLinks.hostOf(request.url).orEmpty()
    val kind = MediaLinks.kindOf(request.url)
    val own = rememberOwnPlayer()
    val web = io.tima.core.media.rememberWebPlayer()
    val order = remember(request) {
        engineOrder(kind, ports.remembered(host)).filter { (it == PlayerEngine.OWN && own != null) || (it == PlayerEngine.WEB && web != null) }
    }
    var at by remember(request) { mutableStateOf(0) }
    var carryMs by remember(request) { mutableStateOf(request.startMs) }
    var failed by remember(request) { mutableStateOf(order.isEmpty()) }
    var attempt by remember(request) { mutableStateOf(0) }
    val engine = order.getOrNull(at)
    val idle = remember { kotlinx.coroutines.flow.MutableStateFlow(io.tima.core.media.OwnPlayerState()) }
    val state by (when (engine) {
        PlayerEngine.OWN -> own?.state
        PlayerEngine.WEB -> web?.state
        null -> null
    } ?: idle).collectAsState()

    /** Следующий движок с той же секунды; кончились — «не играет». */
    fun next(why: PlayerEvent, detail: String) {
        onEvent(why, "$host ${engine?.name?.lowercase()} $detail")
        val pos = state.positionMs.takeIf { it > 0 } ?: carryMs
        if (at + 1 < order.size) {
            carryMs = pos
            at++
            attempt++
            onEvent(PlayerEvent.SWITCHED, "$host → ${order[at].name.lowercase()}")
        } else {
            failed = true
        }
    }

    LaunchedEffect(request, at, attempt) {
        val e = engine ?: return@LaunchedEffect
        val sec = (carryMs / 1000).toInt()
        when (e) {
            PlayerEngine.OWN -> {
                web?.pause()
                val direct = when (kind) {
                    MediaLinks.Kind.RUTUBE, MediaLinks.Kind.VK_VIDEO -> ports.resolve(kind, request.url)
                    else -> request.url
                }
                if (direct == null) {
                    next(PlayerEvent.FAILED, "no-stream")
                    return@LaunchedEffect
                }
                own?.open(direct, carryMs)
            }
            PlayerEngine.WEB -> {
                own?.pause()
                val page = webPageOf(request.url, sec)
                if (page == null) {
                    next(PlayerEvent.FAILED, "no-page")
                    return@LaunchedEffect
                }
                web?.open(page, carryMs)
            }
        }
        onEvent(PlayerEvent.OPENED, "$host ${e.name.lowercase()}")
        // Сторож (§3.4): ни кадра, ни звука за 15 с — не смог.
        delay(PLAYER_WATCH_MS)
        val s = if (e == PlayerEngine.OWN) own?.state?.value else web?.state?.value
        if (s != null && !s.started && s.error == null && !failed) next(PlayerEvent.TIMEOUT, "")
    }
    LaunchedEffect(state.error) { state.error?.let { if (!failed) next(PlayerEvent.FAILED, it) } }
    LaunchedEffect(state.started, engine) {
        if (state.started && engine != null) {
            onEvent(PlayerEvent.STARTED, "$host ${engine.name.lowercase()}")
            ports.remember(host, engine)
        }
    }
    fun play() = if (engine == PlayerEngine.WEB) web?.play() else own?.play()
    fun pause() = if (engine == PlayerEngine.WEB) web?.pause() else own?.pause()
    fun seek(ms: Long) = if (engine == PlayerEngine.WEB) web?.seek(ms) else own?.seek(ms)

    Column(Modifier.fillMaxSize().background(colors.viewer).testTag(PlayerTags.WINDOW)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption(title, fontSize = 15.sp, weight = FontWeight.Bold, color = onVideo, lineOne = true, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clickable(onClick = onClose).testTag(PlayerTags.CLOSE), contentAlignment = Alignment.Center) {
                Caption("✕", fontSize = 20.sp, color = onVideo)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                failed -> Caption(words.playerFailed, color = onVideo, fontSize = 15.sp)
                engine == PlayerEngine.OWN && own != null -> OwnPlayerSurface(own, Modifier.fillMaxSize())
                engine == PlayerEngine.WEB && web != null -> io.tima.core.media.WebPlayerSurface(web, Modifier.fillMaxSize())
            }
            if (!failed && !state.started && engine == PlayerEngine.OWN) Caption(words.playerLoading, color = onVideo, fontSize = 15.sp)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!state.live && state.durationMs > 0) {
                val progress = state.positionMs.toFloat() / state.durationMs
                val marks = request.marks.map { it.atMs.toFloat() / state.durationMs }
                SeekBar(progress, Modifier.fillMaxWidth().height(24.dp).testTag(PlayerTags.BAR), marks) { f ->
                    seek((state.durationMs * f).toLong())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(colors.navigation, CircleShape)
                        .clickable(enabled = !failed) { if (state.playing) pause() else play() }
                        .testTag(PlayerTags.PLAY),
                    contentAlignment = Alignment.Center,
                ) {
                    Caption(if (state.playing) "❚❚" else "▶", fontSize = 18.sp, color = colors.onAccent)
                }
                Caption(
                    if (state.live) words.playerLive else "${voiceTime(state.positionMs)} / ${voiceTime(state.durationMs)}",
                    color = onVideo, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                if (!state.live && engine == PlayerEngine.OWN) {
                    Chip(
                        speedName(state.speed),
                        kind = ChipKind.Quiet,
                        onClick = { own?.setSpeed(when (state.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }) },
                        modifier = Modifier.testTag(PlayerTags.SPEED),
                    )
                }
                // «Другой проигрыватель» — виден всегда, когда движков больше одного (§3.1).
                if (order.size > 1) {
                    Chip(words.playerOther, kind = ChipKind.Quiet, modifier = Modifier.testTag(PlayerTags.OTHER), onClick = {
                        carryMs = state.positionMs.takeIf { it > 0 } ?: carryMs
                        at = (at + 1) % order.size
                        attempt++
                        failed = false
                        onEvent(PlayerEvent.SWITCHED, "$host → ${order[at].name.lowercase()} hand")
                    })
                }
                Chip(words.playerBrowser, kind = ChipKind.Quiet, onClick = {
                    onEvent(PlayerEvent.TO_BROWSER, host)
                    onBrowser(request.url)
                }, modifier = Modifier.testTag(PlayerTags.BROWSER))
            }
            if (request.marks.isNotEmpty()) PlayerMarks(request.marks, state.positionMs) { ms -> seek(ms); play() }
        }
    }
}

/** Метки в окне (§3.6): текущая подсвечена по ходу просмотра, нажатие перематывает. */
@Composable
private fun PlayerMarks(marks: List<io.tima.domain.chat.MediaMark>, positionMs: Long, onSeek: (Long) -> Unit) {
    val colors = Tima.colors
    val current = currentMark(marks, positionMs)
    Column(
        Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()).testTag("player:marks"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((i, m) in marks.withIndex()) {
            Row(
                Modifier.fillMaxWidth().background(if (i == current) colors.navigation.copy(alpha = 0.35f) else colors.viewer, RoundedCornerShape(6.dp))
                    .clickable { onSeek(m.atMs) }.padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption(io.tima.domain.chat.markTime(m.atMs), fontSize = 14.sp, weight = FontWeight.SemiBold, color = colors.link)
                Caption(m.text, fontSize = 14.sp, color = io.tima.core.ui.TimaFixed.onVideo, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Полоса проигрывания: нажатие и перетаскивание — перемотка к доле [onSeek]. */
@Composable
internal fun SeekBar(progress: Float, modifier: Modifier, marks: List<Float> = emptyList(), onSeek: (Float) -> Unit) {
    val colors = Tima.colors
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> onSeek((c.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        val h = 4.dp.toPx()
        val y = (size.height - h) / 2
        drawRoundRect(colors.line, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2, h / 2))
        drawRoundRect(colors.navigation, Offset(0f, y), Size(size.width * progress.coerceIn(0f, 1f), h), CornerRadius(h / 2, h / 2))
        // Отрезки по меткам (Р12): тонкий зазор на месте каждой метки.
        for (m in marks) drawRect(colors.viewer, Offset(size.width * m - 1.dp.toPx(), y), Size(2.dp.toPx(), h))
        drawCircle(io.tima.core.ui.TimaFixed.onVideo, 7.dp.toPx(), Offset(size.width * progress.coerceIn(0f, 1f), size.height / 2))
    }
}
