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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
data class PlayRequest(val url: String, val title: String = "", val startMs: Long = 0)

/** Что случилось в окне — для журнала (`PLAYER`); слова — у пишущего журнал. */
enum class PlayerEvent {
    OPENED,
    STARTED,

    /** Движок сказал «не могу». */
    FAILED,

    /** Сторож: за [PLAYER_WATCH_MS] ни кадра, ни звука. */
    TIMEOUT,
    TO_BROWSER,
}

/** Сколько ждать первого кадра или звука, прежде чем считать, что движок не смог (§3.4). */
const val PLAYER_WATCH_MS = 15_000L

object PlayerTags {
    const val WINDOW = "player:window"
    const val PLAY = "player:play"
    const val BAR = "player:bar"
    const val SPEED = "player:speed"
    const val BROWSER = "player:browser"
    const val CLOSE = "player:close"
}

/**
 * Окно проигрывания (ПЛАН-(ВМ) §3.1, ВМ5): видео и звук по ссылке — своим движком (Media3 на
 * Android, Media Foundation на ПК). Не смог — сказать и предложить браузер. Браузерный движок и
 * смена движков — ВМ6, ВМ7.
 */
@Composable
fun PlayerWindow(
    request: PlayRequest,
    onClose: () -> Unit,
    onBrowser: (String) -> Unit,
    onEvent: (what: PlayerEvent, detail: String) -> Unit = { _, _ -> },
) {
    val title = request.title.ifBlank { MediaLinks.hostOf(request.url).orEmpty() }
    MediaWindow(title = title, onClose = onClose) {
        PlayerContent(request, title, onClose, onBrowser, onEvent)
    }
}

@Composable
private fun PlayerContent(
    request: PlayRequest,
    title: String,
    onClose: () -> Unit,
    onBrowser: (String) -> Unit,
    onEvent: (PlayerEvent, String) -> Unit,
) {
    val colors = Tima.colors
    val words = Tima.words.chat
    val player = rememberOwnPlayer()
    var failed by remember(request) { mutableStateOf<String?>(if (player == null) "no-engine" else null) }
    LaunchedEffect(player, request) {
        player ?: return@LaunchedEffect
        player.open(request.url, request.startMs)
        onEvent(PlayerEvent.OPENED, MediaLinks.hostOf(request.url).orEmpty())
    }
    val state by (player?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(io.tima.core.media.OwnPlayerState()) }).collectAsState()
    LaunchedEffect(state.error) {
        state.error?.let {
            failed = it
            onEvent(PlayerEvent.FAILED, it)
        }
    }
    LaunchedEffect(state.started) { if (state.started) onEvent(PlayerEvent.STARTED, "") }
    // Сторож (§3.4): ни кадра, ни звука за 15 с — не смог.
    LaunchedEffect(player, request) {
        delay(PLAYER_WATCH_MS)
        if (player != null && !player.state.value.started && failed == null) {
            failed = "timeout"
            onEvent(PlayerEvent.TIMEOUT, MediaLinks.hostOf(request.url).orEmpty())
        }
    }
    Column(Modifier.fillMaxSize().background(colors.viewer).testTag(PlayerTags.WINDOW)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Caption(title, fontSize = 15.sp, weight = FontWeight.Bold, color = io.tima.core.ui.TimaFixed.onVideo, lineOne = true, modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clickable(onClick = onClose).testTag(PlayerTags.CLOSE), contentAlignment = Alignment.Center) {
                Caption("✕", fontSize = 20.sp, color = io.tima.core.ui.TimaFixed.onVideo)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (player != null) OwnPlayerSurface(player, Modifier.fillMaxSize())
            when {
                failed != null -> Caption(words.playerFailed, color = io.tima.core.ui.TimaFixed.onVideo, fontSize = 15.sp)
                !state.started && (state.buffering || !state.playing) -> Caption(words.playerLoading, color = io.tima.core.ui.TimaFixed.onVideo, fontSize = 15.sp)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!state.live && state.durationMs > 0) {
                val progress = state.positionMs.toFloat() / state.durationMs
                SeekBar(progress, Modifier.fillMaxWidth().height(24.dp).testTag(PlayerTags.BAR)) { f ->
                    player?.seek((state.durationMs * f).toLong())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(colors.navigation, CircleShape)
                        .clickable(enabled = player != null && failed == null) { if (state.playing) player?.pause() else player?.play() }
                        .testTag(PlayerTags.PLAY),
                    contentAlignment = Alignment.Center,
                ) {
                    Caption(if (state.playing) "❚❚" else "▶", fontSize = 18.sp, color = colors.onAccent)
                }
                Caption(
                    if (state.live) words.playerLive else "${voiceTime(state.positionMs)} / ${voiceTime(state.durationMs)}",
                    color = io.tima.core.ui.TimaFixed.onVideo, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
                if (!state.live) {
                    Chip(
                        when (state.speed) { 1.5f -> "1,5×"; 2f -> "2×"; else -> "1×" },
                        kind = ChipKind.Quiet,
                        onClick = { player?.setSpeed(when (state.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }) },
                        modifier = Modifier.testTag(PlayerTags.SPEED),
                    )
                }
                Chip(words.playerBrowser, kind = ChipKind.Quiet, onClick = {
                    onEvent(PlayerEvent.TO_BROWSER, MediaLinks.hostOf(request.url).orEmpty())
                    onBrowser(request.url)
                }, modifier = Modifier.testTag(PlayerTags.BROWSER))
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
