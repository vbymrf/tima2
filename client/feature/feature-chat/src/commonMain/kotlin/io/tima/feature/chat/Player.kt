package io.tima.feature.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.FullScreenMode
import io.tima.core.media.MediaWindow
import io.tima.core.media.OwnPlayerSurface
import io.tima.core.media.ScreenTurn
import io.tima.core.media.rememberOwnPlayer
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.TextPlace
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.M3u8
import io.tima.domain.chat.MediaLinks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

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

    /** Сменили движок — сами после отказа или кнопкой «Браузерный» / «Встроенный проигрыватель». */
    SWITCHED,
    TO_BROWSER,

    /** Проигрыватель сайта ушёл с нашего ролика — на другой ролик или страницу (4а). */
    AWAY,

    /** «Перезапустить»: тот же движок, наше видео, та же секунда (4а). */
    RESTARTED,

    /** Выбрано качество своего движка: «Авто» или высота кадра (8а). */
    QUALITY,
}

/** Сколько ждать первого кадра или звука, прежде чем считать, что движок не смог (§3.4). */
const val PLAYER_WATCH_MS = 15_000L

/** Сколько видны кнопки поверх видео в полном экране после касания. */
private const val OVERLAY_MS = 3_000L

/** Движок окна (§3.2): свой (Media3, Media Foundation) или браузерный (WebView, WebView2). */
enum class PlayerEngine { OWN, WEB }

/**
 * Что окну нужно снаружи (§3.3, §3.4): поток сайта для своего движка, качества потока и память по
 * сайту — с каким движком видео пошло в прошлый раз.
 */
class PlayerPorts(
    /** Поток HLS Rutube или VK для своего движка; не вышло — `null`. */
    val resolve: suspend (kind: MediaLinks.Kind, url: String) -> String? = { _, _ -> null },
    /** Качества главного списка m3u8 (8а) потока ссылки вида [kind]; не главный или не вышло — пусто. */
    val variants: suspend (kind: MediaLinks.Kind?, url: String) -> List<M3u8.Variant> = { _, _ -> emptyList() },
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
    /** Скорость в меню своего проигрывателя: `player:speed.1`, `player:speed.1.5`, `player:speed.2`. */
    fun speed(value: Float) = "player:speed." + (if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString())
    const val BROWSER = "player:browser"
    const val OTHER = "player:other"
    const val CLOSE = "player:close"
    const val RESTART = "player:restart"
    const val SOUND = "player:sound"
    const val MARKS = "player:marks.open"
    const val PEEK = "player:peek"
    const val FULL = "player:full"
    /** Кнопка своего проигрывателя справа вверху — меню качества и скорости (заказчик 2026-10-24). */
    const val SETTINGS = "player:settings"
    const val SETTINGS_MENU = "player:settings.menu"
    fun quality(height: Int) = "player:quality.$height"
}

/**
 * Полный экран окна (заказчик 2026-10-23, 6–7): кнопкой «На весь экран» или телефоном боком. После
 * выхода окно держится вертикально, пока телефон не поставят вертикально ([upright]).
 */
private class PlayerScreen {
    var full by mutableStateOf(false)
    var landscape by mutableStateOf(false)
    var upright by mutableStateOf(false)
    val active get() = full || (landscape && !upright)

    fun enter() {
        full = true
        upright = false
    }

    fun exit() {
        full = false
        upright = true
    }
}

/**
 * Окно проигрывания (ПЛАН-(ВМ) §3.1–§3.4): видео и звук по ссылке — двумя движками. Не смог
 * (ошибка или сторож 15 с) — окно само берёт другой движок с той же секунды; «Браузерный» или
 * «Встроенный проигрыватель» — то же руками; ни один не смог — «Открыть в браузере». Движок, с которым
 * пошло, запоминается по сайту. «Назад» в полном экране выходит из него, а не закрывает окно.
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
    val screen = remember(request) { PlayerScreen() }
    MediaWindow(title = title, onClose = { if (screen.active) screen.exit() else onClose() }) {
        PlayerContent(request, title, screen, onClose, onBrowser, onEvent, ports)
    }
}

@Composable
private fun PlayerContent(
    request: PlayRequest,
    title: String,
    screen: PlayerScreen,
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
    // Поток своего движка и его качества (8а): узнаются один раз, «Авто» (0) — по умолчанию.
    var ownStream by remember(request) { mutableStateOf<String?>(null) }
    var variants by remember(request) { mutableStateOf<List<M3u8.Variant>>(emptyList()) }
    var variantsAsked by remember(request) { mutableStateOf(false) }
    var quality by remember(request) { mutableStateOf(0) }
    var qualityOpen by remember(request) { mutableStateOf(false) }
    // Последняя секунда нашего ролика — к ней возвращает «Перезапустить», когда сайт увёл на другой (4а).
    var ourMs by remember(request) { mutableStateOf(request.startMs) }
    // Звук — со звуком по умолчанию, и у VK тоже: он сам запускается без звука (заказчик 2026-10-23).
    var muted by remember(request) { mutableStateOf(false) }
    // Палец на полосе: куда перемотает, когда отпустят (доля), и кадр там — у прямого файла.
    var dragAt by remember(request) { mutableStateOf<Float?>(null) }
    var peekImage by remember(request) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // Список меток поверх видео в полном экране (3а).
    var marksOpen by remember(request) { mutableStateOf(false) }
    val peek = io.tima.core.media.rememberFramePeek(if (kind == MediaLinks.Kind.VIDEO_FILE) request.url else null)
    val engine = order.getOrNull(at)
    val idle = remember { kotlinx.coroutines.flow.MutableStateFlow(io.tima.core.media.OwnPlayerState()) }
    val state by (when (engine) {
        PlayerEngine.OWN -> own?.state
        PlayerEngine.WEB -> web?.state
        null -> null
    } ?: idle).collectAsState()

    /** С какой секунды продолжать: ушли на чужой ролик — с последней нашей. */
    fun here(): Long = if (state.elsewhere) ourMs else state.positionMs.takeIf { it > 0 } ?: carryMs

    /** Следующий движок с той же секунды; кончились — «не играет». */
    fun next(why: PlayerEvent, detail: String) {
        onEvent(why, "$host ${engine?.name?.lowercase()} $detail")
        val pos = here()
        if (at + 1 < order.size) {
            carryMs = pos
            at++
            attempt++
            onEvent(PlayerEvent.SWITCHED, "$host → ${order[at].name.lowercase()}")
        } else {
            failed = true
        }
    }

    /** Тот же движок заново: наше видео с нашей секунды (4а) или другое качество (8а). */
    fun reopen(why: PlayerEvent, detail: String) {
        onEvent(why, "$host ${engine?.name?.lowercase()} $detail".trimEnd())
        carryMs = here()
        attempt++
        failed = false
    }

    /** Список качеств один раз на окно; прервали (сменили движок) — спросится снова. */
    suspend fun askVariants(stream: String) {
        variantsAsked = true
        try {
            variants = ports.variants(kind, stream)
        } catch (e: kotlinx.coroutines.CancellationException) {
            variantsAsked = false
            throw e
        }
    }

    LaunchedEffect(request, at, attempt) {
        val e = engine ?: return@LaunchedEffect
        val sec = (carryMs / 1000).toInt()
        when (e) {
            PlayerEngine.OWN -> {
                web?.pause()
                val stream = ownStream ?: when (kind) {
                    MediaLinks.Kind.RUTUBE, MediaLinks.Kind.VK_VIDEO -> ports.resolve(kind, request.url)
                    else -> request.url
                }
                if (stream == null) {
                    next(PlayerEvent.FAILED, "no-stream")
                    return@LaunchedEffect
                }
                ownStream = stream
                // Список качеств — у Rutube и VK всегда (их поток — HLS, адрес не всегда кончается на m3u8),
                // у ссылки из сообщения — когда это m3u8.
                val hls = kind == MediaLinks.Kind.RUTUBE || kind == MediaLinks.Kind.VK_VIDEO || "m3u8" in stream.substringBefore('?').lowercase()
                if (!variantsAsked && hls) launch { askVariants(stream) }
                own?.open(variants.firstOrNull { it.quality == quality }?.url ?: stream, carryMs, hls = hls)
            }
            PlayerEngine.WEB -> {
                own?.pause()
                val page = webPageOf(request.url, sec)
                if (page == null) {
                    next(PlayerEvent.FAILED, "no-page")
                    return@LaunchedEffect
                }
                web?.open(page, carryMs)
                // Rutube и VK: размер кадра — из их списка качеств, а не с картинки (там бывает реклама):
                // вертикальное видео в полном экране не поворачиваем (3а). Пригодится и своему движку.
                if (!variantsAsked && (kind == MediaLinks.Kind.RUTUBE || kind == MediaLinks.Kind.VK_VIDEO)) {
                    launch {
                        val stream = ownStream ?: ports.resolve(kind, request.url)?.also { ownStream = it }
                        if (stream != null) askVariants(stream)
                    }
                }
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
    LaunchedEffect(state.positionMs, state.elsewhere) {
        if (!state.elsewhere && state.started && state.positionMs > 0) ourMs = state.positionMs
    }
    LaunchedEffect(state.elsewhere) { if (state.elsewhere && engine != null) onEvent(PlayerEvent.AWAY, "$host ${engine.name.lowercase()}") }
    // Звук — движку при смене, при нажатии и ещё раз, когда видео пошло: VK включает свой «без звука» при старте.
    LaunchedEffect(muted, engine, attempt, state.started) {
        when (engine) {
            PlayerEngine.OWN -> own?.setMuted(muted)
            PlayerEngine.WEB -> web?.setMuted(muted)
            null -> Unit
        }
    }
    // Кадр под пальцем: последний запрошенный, промежуточные пропускаются — чтение кадра по сети долгое.
    LaunchedEffect(peek) {
        val p = peek ?: return@LaunchedEffect
        snapshotFlow { dragAt?.let { (it * state.durationMs).toLong() } }
            .filterNotNull()
            .conflate()
            .collect { ms -> p.frame(ms)?.let { peekImage = it } }
    }
    // Пока видео идёт, телефон не засыпает (заказчик 2026-10-23); пауза — экран гаснет по настройке.
    io.tima.core.media.KeepScreenOn(state.playing && !failed)
    fun play() = if (engine == PlayerEngine.WEB) web?.play() else own?.play()
    fun pause() = if (engine == PlayerEngine.WEB) web?.pause() else own?.pause()
    fun seek(ms: Long) = if (engine == PlayerEngine.WEB) web?.seek(ms) else own?.seek(ms)

    // Горизонтальное видео в полном экране кладёт телефон боком; вертикальное (Shorts) — как держат.
    // Размер — из сведений о видео, не с картинки (3а): свой движок и файл браузерным — кадр; Rutube и VK —
    // их список качеств; YouTube размера не отдаёт — только Shorts по адресу. Не знаем — горизонтальное.
    val sized = variants.firstOrNull()
    val wide = when {
        engine == PlayerEngine.OWN || kind == MediaLinks.Kind.VIDEO_FILE -> state.width == 0 || state.width >= state.height
        sized != null -> sized.width >= sized.height
        else -> "/shorts/" !in request.url
    }
    val turn = when {
        screen.full && wide -> ScreenTurn.LANDSCAPE
        screen.upright -> ScreenTurn.PORTRAIT
        else -> ScreenTurn.FREE
    }
    FullScreenMode(screen.active, turn, onUpright = { screen.upright = false })

    // Перед «Перезапустить» и «На весь экран» (заказчик 2026-10-23).
    val sound: @Composable () -> Unit = {
        PlayerIconButton(
            if (muted) PlayerIcon.SOUND_OFF else PlayerIcon.SOUND_ON,
            if (muted) words.playerUnmute else words.playerMute,
            lit = false, tag = PlayerTags.SOUND,
        ) { muted = !muted }
    }
    val restart: @Composable () -> Unit = {
        // Есть всегда; горит зелёным, когда проигрыватель сайта увёл с нашего ролика (заказчик 2026-10-23, 4а).
        PlayerIconButton(PlayerIcon.RESTART, words.playerRestart, lit = state.elsewhere, tag = PlayerTags.RESTART) {
            reopen(PlayerEvent.RESTARTED, if (state.elsewhere) "away" else "")
        }
    }
    // Видео грузится («кружится») — вокруг ▶ бежит белая дуга (заказчик 2026-10-23).
    val spinning = !failed && engine != null && (state.buffering || !state.started)
    val playButton: @Composable () -> Unit = {
        val turn = if (spinning) {
            rememberInfiniteTransition(label = "spin").animateFloat(
                0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "spin",
            ).value
        } else {
            null
        }
        Box(
            Modifier.size(44.dp)
                .drawWithContent {
                    drawContent()
                    if (turn != null) {
                        val gap = 4.dp.toPx()
                        drawArc(
                            onVideo, startAngle = turn - 90f, sweepAngle = 100f, useCenter = false,
                            topLeft = Offset(-gap, -gap), size = Size(size.width + gap * 2, size.height + gap * 2),
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
                .background(colors.navigation, CircleShape)
                .clickable(enabled = !failed) { if (state.playing) pause() else play() }
                .testTag(PlayerTags.PLAY),
            contentAlignment = Alignment.Center,
        ) {
            Caption(if (state.playing) "❚❚" else "▶", fontSize = 18.sp, color = colors.onAccent)
        }
    }
    // Часы — как у меток: фильм на 2 часа 18 минут — «2:18:51», а не «138:51» (Samsung, 2026-10-23).
    val time = if (state.live) words.playerLive else "${io.tima.domain.chat.markTime(state.positionMs)} / ${io.tima.domain.chat.markTime(state.durationMs)}"
    val bar: @Composable () -> Unit = {
        if (!state.live && state.durationMs > 0) {
            val progress = dragAt ?: (state.positionMs.toFloat() / state.durationMs)
            val marks = request.marks.map { it.atMs.toFloat() / state.durationMs }
            // Палец держит полосу — над ней время (и кадр у файла), перемотка — когда отпустили (заказчик
            // 2026-10-23). Подсказка рисуется поверх видео и окно не сдвигает.
            Box(Modifier.fillMaxWidth()) {
                // Непроигранная часть — светлой полосой: цветом линий на чёрном её не было видно (заказчик 2026-10-23).
                // Чужой ролик — полоса блёклая и не перематывает: время уже не наше.
                SeekBar(
                    progress, Modifier.fillMaxWidth().height(24.dp).alpha(if (state.elsewhere) 0.4f else 1f).testTag(PlayerTags.BAR), marks,
                    track = onVideo.copy(alpha = 0.35f),
                    onDrag = { f ->
                        if (f == null) peekImage = null
                        dragAt = if (state.elsewhere) null else f
                    },
                ) { f -> if (!state.elsewhere) seek((state.durationMs * f).toLong()) }
                dragAt?.let { f -> SeekPeek(f, (state.durationMs * f).toLong(), peekImage, Modifier.align(Alignment.BottomStart)) }
            }
        }
    }

    // Название — размером текста сообщений, по их группе в «Шрифтах и размерах» (заказчик 2026-10-24).
    val titleText: @Composable (Modifier) -> Unit = { m ->
        ProvidePlace(TextPlace.MESSAGES) {
            if (LocalLightButtons.current) {
                Box(m) {
                    Caption(
                        title, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = colors.viewer, lineOne = true,
                        modifier = Modifier.background(onVideo, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            } else {
                Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, color = onVideo, lineOne = true, modifier = m)
            }
        }
    }
    // Крестик — на сером круге, как все кнопки окна; в полном экране тоже (заказчик 2026-10-24).
    val close: @Composable () -> Unit = {
        PlayerIconButton(PlayerIcon.CLOSE, Tima.words.call.close, lit = false, tag = PlayerTags.CLOSE, onClick = onClose)
    }

    // Свой проигрыватель: справа вверху, левее ✕, — меню качества и скорости (заказчик 2026-10-24). Качеств
    // нет — только скорость; эфир без качеств — кнопки нет.
    val hasQuality = engine == PlayerEngine.OWN && variants.size > 1
    val hasSpeed = engine == PlayerEngine.OWN && !state.live
    val settings: @Composable () -> Unit = {
        if (hasQuality || hasSpeed) {
            PlayerIconButton(PlayerIcon.SETTINGS, words.playerSettings, lit = qualityOpen, tag = PlayerTags.SETTINGS) { qualityOpen = !qualityOpen }
        }
    }
    // Справа вверху своего проигрывателя: меню, ✕ — во всех видах (заказчик 2026-10-24).
    val corner: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            settings()
            close()
        }
    }

    // Касание где угодно в полном экране показывает кнопки поверх видео на 3 с; на паузе они видны всегда.
    var touches by remember { mutableStateOf(0) }
    var overlay by remember { mutableStateOf(true) }
    LaunchedEffect(touches, screen.active) {
        overlay = true
        delay(OVERLAY_MS)
        overlay = false
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(colors.viewer).testTag(PlayerTags.WINDOW)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) touches++
                    }
                }
            },
    ) {
        val sideways = maxWidth > maxHeight
        LaunchedEffect(sideways) { screen.landscape = io.tima.core.media.turnsWithPhone && sideways }
        val full = screen.active
        // Отступы под строку состояния и кнопки навигации — только в обычном окне: на Redmi название и ✕
        // уходили под часы, последняя метка — под кнопки (2026-10-23). В полном экране полосы спрятаны.
        Column(Modifier.fillMaxSize().then(if (full) Modifier else Modifier.systemBarsPadding())) {
            if (!full) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    titleText(Modifier.weight(1f))
                    corner()
                }
            }
            // Место видео — одно и то же в обоих видах: движок не пересоздаётся при входе в полный экран.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    failed -> Caption(words.playerFailed, color = onVideo, fontSize = 15.sp)
                    engine == PlayerEngine.OWN && own != null -> OwnPlayerSurface(own, Modifier.fillMaxSize())
                    engine == PlayerEngine.WEB && web != null -> io.tima.core.media.WebPlayerSurface(web, Modifier.fillMaxSize())
                }
                if (!failed && !state.started && engine == PlayerEngine.OWN) Caption(words.playerLoading, color = onVideo, fontSize = 15.sp)
            }
            if (!full) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    bar()
                    // ▶ и время слева; звук, «Перезапустить» и «На весь экран» — справа, в том же ряду (заказчик
                    // 2026-10-23). Зазоры поуже: на 360 точках иначе не помещалось «4:38 / 2:18:51».
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        playButton()
                        Caption(time, color = onVideo, fontSize = 13.sp, lineOne = true, modifier = Modifier.weight(1f))
                        sound()
                        restart()
                        PlayerIconButton(PlayerIcon.FULL, words.playerFull, lit = false, tag = PlayerTags.FULL) { screen.enter() }
                    }
                    // Кнопки — своим рядом с прокруткой: в одном ряду с временем на экране 720 точек время
                    // сжималось в столбик по букве (Redmi, 2026-10-23).
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Смена движка — видна всегда, когда движков больше одного (§3.1); надпись называет тот,
                        // на который переключит: «Браузерный» или «Встроенный проигрыватель» (заказчик 2026-10-23).
                        if (order.size > 1) {
                            val to = order[(at + 1) % order.size]
                            Chip(if (to == PlayerEngine.WEB) words.playerToWeb else words.playerToOwn, kind = ChipKind.Quiet, modifier = Modifier.testTag(PlayerTags.OTHER), onClick = {
                                carryMs = here()
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
                    if (request.marks.isNotEmpty()) {
                        PlayerMarks(request.marks, state.positionMs, enabled = !state.elsewhere) { ms -> seek(ms); play() }
                    }
                }
            }
        }
        // Полный экран (заказчик 2026-10-23, 3а, 4а и следом): ▶ от левого края, сразу за ним время в поле
        // постоянной ширины (с часами, если видео длиннее часа), за временем — «Метки» (если есть), звук,
        // «Перезапустить», выход — одним рядом. Название — строкой выше. Низ — кнопкам сайта, у своего движка —
        // нашей полосе. Свой движок с вертикальным видео (телефон стоит) — всё наше внизу, как в окне.
        if (full && (overlay || !state.playing || dragAt != null || marksOpen || qualityOpen)) {
            val shade = colors.viewer.copy(alpha = 0.5f)
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            val density = androidx.compose.ui.platform.LocalDensity.current
            val template = when {
                state.live -> words.playerLive
                state.durationMs >= 3_600_000 -> "0:00:00 / 0:00:00"
                else -> "00:00 / 00:00"
            }
            val slot = remember(template, density) {
                with(density) { measurer.measure(template, androidx.compose.ui.text.TextStyle(fontSize = 14.sp)).size.width.toDp() + 8.dp }
            }
            // Ряд кнопок: ▶, время в поле постоянной ширины, «Метки» (если есть), звук; затем «Перезапустить»,
            // выход из полного экрана и ✕. Телефон боком — одним рядом, ✕ правее выхода; стоит — места нет:
            // последние три вторым рядом, все сразу (заказчик 2026-10-24).
            val controls: @Composable (Modifier, Boolean, Boolean) -> Unit = { m, oneRow, centered ->
                val along = if (centered) Alignment.CenterHorizontally else Alignment.Start
                val first: @Composable () -> Unit = {
                    playButton()
                    if (LocalLightButtons.current) {
                        Box(Modifier.width(slot + 12.dp).background(onVideo, RoundedCornerShape(14.dp)).padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Caption(time, color = colors.viewer, fontSize = 14.sp, lineOne = true)
                        }
                    } else {
                        Caption(time, color = onVideo, fontSize = 14.sp, lineOne = true, modifier = Modifier.width(slot))
                    }
                }
                val icons: @Composable () -> Unit = {
                    if (request.marks.isNotEmpty()) {
                        PlayerIconButton(PlayerIcon.MARKS, words.playerMarks, lit = marksOpen, tag = PlayerTags.MARKS) { marksOpen = !marksOpen }
                    }
                    sound()
                }
                val last: @Composable () -> Unit = {
                    restart()
                    PlayerIconButton(PlayerIcon.FULL_EXIT, words.playerFullExit, lit = false, tag = PlayerTags.FULL) { screen.exit() }
                    if (engine != PlayerEngine.OWN) close()
                }
                if (oneRow) {
                    Row(m, horizontalArrangement = Arrangement.spacedBy(12.dp, along), verticalAlignment = Alignment.CenterVertically) {
                        first()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            icons()
                            last()
                        }
                    }
                } else {
                    Column(m, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = along) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            first()
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { icons() }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { last() }
                    }
                }
            }
            val ownUpright = engine == PlayerEngine.OWN && !sideways
            if (ownUpright) {
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().background(shade).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    titleText(Modifier.weight(1f))
                    corner()
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(shade).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    bar()
                    controls(Modifier.fillMaxWidth(), false, false)
                }
            } else {
                // Кнопки вверху — группой по середине (заказчик 2026-10-24). Правый верхний угол не перекрываем:
                // там у проигрывателя сайта две кнопки (качество, настройки) — название до него не доходит, а
                // большой отступ под названием опускает наши кнопки ниже тех двух.
                // Браузерный — без затемняющей панели, кнопки на белом круге (заказчик 2026-10-24): лучше видно и
                // системные кнопки не затемняются. Встроенный — как был, на панели.
                val bare = engine == PlayerEngine.WEB
                CompositionLocalProvider(LocalLightButtons provides bare) {
                    Column(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().then(if (bare) Modifier else Modifier.background(shade))
                            .padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(TITLE_GAP),
                    ) {
                        if (bare) {
                            // Браузерный — без названия (заказчик 2026-10-24); место под ним остаётся: оно опускает
                            // наши кнопки ниже двух кнопок сайта в углу.
                            titleText(Modifier.padding(end = SITE_CORNER).alpha(0f))
                        } else {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                titleText(Modifier.weight(1f))
                                corner()
                            }
                        }
                        controls(Modifier.fillMaxWidth(), sideways, true)
                    }
                }
                if (engine != PlayerEngine.WEB) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(shade).padding(horizontal = 12.dp, vertical = 8.dp)) { bar() }
                }
            }
            // Метки — списком справа поверх видео; нажатие перематывает и закрывает список (3а).
            if (marksOpen && request.marks.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(top = 64.dp, bottom = 16.dp, end = 12.dp).width(300.dp)
                        .background(colors.viewer.copy(alpha = 0.85f), RoundedCornerShape(10.dp)).padding(8.dp),
                ) {
                    PlayerMarks(request.marks, state.positionMs, enabled = !state.elsewhere, maxHeight = 600.dp) { ms ->
                        seek(ms)
                        play()
                        marksOpen = false
                    }
                }
            }
        }
        // Меню качества и скорости своего проигрывателя — под кнопкой справа вверху (заказчик 2026-10-24).
        if (qualityOpen && (hasQuality || hasSpeed)) {
            Column(
                Modifier.align(Alignment.TopEnd).then(if (full) Modifier else Modifier.systemBarsPadding())
                    .padding(top = 60.dp, end = 12.dp).width(200.dp)
                    .background(colors.viewer.copy(alpha = 0.9f), RoundedCornerShape(10.dp)).padding(8.dp)
                    .testTag(PlayerTags.SETTINGS_MENU),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val item: @Composable (String, Boolean, String, () -> Unit) -> Unit = { label, on, tag, pick ->
                    Caption(
                        label, fontSize = TimaType.sz4, weight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) colors.onAccent else onVideo, lineOne = true,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (on) colors.navigation else colors.viewer.copy(alpha = 0f), RoundedCornerShape(6.dp))
                            .clickable(onClick = pick).padding(horizontal = 8.dp, vertical = 6.dp).testTag(tag),
                    )
                }
                ProvidePlace(TextPlace.MENU) {
                    if (hasQuality) {
                        Caption(words.playerQuality, fontSize = TimaType.sz5, color = onVideo.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        for (h in listOf(0) + variants.map { it.quality }) {
                            item(if (h == 0) words.playerAuto else "${h}p", h == quality, PlayerTags.quality(h)) {
                                qualityOpen = false
                                if (h != quality) {
                                    quality = h
                                    reopen(PlayerEvent.QUALITY, if (h == 0) "auto" else "${h}p")
                                }
                            }
                        }
                    }
                    if (hasSpeed) {
                        Caption(words.playerSpeed, fontSize = TimaType.sz5, color = onVideo.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        for (v in listOf(1f, 1.5f, 2f)) {
                            item(speedName(v), state.speed == v, PlayerTags.speed(v)) {
                                qualityOpen = false
                                own?.setSpeed(v)
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class PlayerIcon { MARKS, SOUND_ON, SOUND_OFF, RESTART, FULL, FULL_EXIT, CLOSE, SETTINGS }

/**
 * Кнопки, время и название — на белом, без затемняющей панели: браузерный проигрыватель в полном экране
 * (заказчик 2026-10-24).
 */
private val LocalLightButtons = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Правый верхний угол — две кнопки проигрывателя сайта (качество, настройки): наше туда не заходит. */
private val SITE_CORNER = 104.dp

/** Отступ под названием в полном экране: наши кнопки — ниже двух кнопок сайта в углу. */
private val TITLE_GAP = 16.dp

/**
 * Подсказка над полосой, пока палец её держит: кадр (у прямого файла, когда успел прочитаться) и время.
 * Стоит над точкой пальца, не выходя за края полосы.
 */
@Composable
private fun SeekPeek(fraction: Float, atMs: Long, image: androidx.compose.ui.graphics.ImageBitmap?, modifier: Modifier) {
    val colors = Tima.colors
    val onVideo = io.tima.core.ui.TimaFixed.onVideo
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = if (image != null) 160.dp else 84.dp
        val x = (maxWidth * fraction - w / 2).coerceIn(0.dp, (maxWidth - w).coerceAtLeast(0.dp))
        Column(
            Modifier.offset(x = x, y = -(if (image != null) 120.dp else 40.dp)).width(w)
                .background(colors.viewer.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(4.dp).testTag(PlayerTags.PEEK),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (image != null) {
                androidx.compose.foundation.Image(
                    image, contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(6.dp)),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            Caption(io.tima.domain.chat.markTime(atMs), fontSize = 14.sp, weight = FontWeight.SemiBold, color = onVideo, lineOne = true)
        }
    }
}

/**
 * Кнопка-значок окна — без слов (заказчик 2026-10-23): «Перезапустить» (круговая стрелка), «На весь экран»
 * (уголки наружу), выход (уголки внутрь), закрыть окно (крестик). [lit] — горит зелёным. Подпись — для озвучки.
 */
@Composable
private fun PlayerIconButton(icon: PlayerIcon, label: String, lit: Boolean, tag: String, onClick: () -> Unit) {
    val colors = Tima.colors
    val light = LocalLightButtons.current
    val ink = when {
        lit -> colors.onAccent
        light -> colors.viewer
        else -> io.tima.core.ui.TimaFixed.onVideo
    }
    val ground = when {
        lit -> colors.navigation
        light -> io.tima.core.ui.TimaFixed.onVideo
        else -> io.tima.core.ui.TimaFixed.onVideo.copy(alpha = 0.18f)
    }
    Box(
        Modifier.size(40.dp).background(ground, CircleShape)
            .clickable(onClick = onClick).semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val w = 2.2.dp.toPx()
            val s = size.minDimension
            val stroke = Stroke(width = w, cap = StrokeCap.Round)
            when (icon) {
                PlayerIcon.MARKS -> {
                    // Список: три строки с точками слева.
                    for (k in 0..2) {
                        val y = s * (0.22f + 0.28f * k)
                        drawCircle(ink, w * 0.8f, Offset(s * 0.12f, y))
                        drawLine(ink, Offset(s * 0.32f, y), Offset(s * 0.92f, y), w, StrokeCap.Round)
                    }
                }
                PlayerIcon.SOUND_ON, PlayerIcon.SOUND_OFF -> {
                    // Динамик: коробка и раструб; включён — две волны, выключен — крестик.
                    val speaker = Path().apply {
                        moveTo(s * 0.08f, s * 0.38f)
                        lineTo(s * 0.26f, s * 0.38f)
                        lineTo(s * 0.50f, s * 0.16f)
                        lineTo(s * 0.50f, s * 0.84f)
                        lineTo(s * 0.26f, s * 0.62f)
                        lineTo(s * 0.08f, s * 0.62f)
                        close()
                    }
                    drawPath(speaker, ink)
                    if (icon == PlayerIcon.SOUND_ON) {
                        for (r in listOf(s * 0.18f, s * 0.34f)) {
                            drawArc(ink, -45f, 90f, false, topLeft = Offset(s * 0.5f - r, s / 2 - r), size = Size(r * 2, r * 2), style = stroke)
                        }
                    } else {
                        drawLine(ink, Offset(s * 0.62f, s * 0.36f), Offset(s * 0.92f, s * 0.64f), w, StrokeCap.Round)
                        drawLine(ink, Offset(s * 0.92f, s * 0.36f), Offset(s * 0.62f, s * 0.64f), w, StrokeCap.Round)
                    }
                }
                PlayerIcon.RESTART -> {
                    val r = s * 0.38f
                    val c = Offset(s / 2, s / 2)
                    drawArc(ink, startAngle = -60f, sweepAngle = 300f, useCenter = false, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = stroke)
                    // Наконечник на конце дуги — по ходу часовой стрелки.
                    val a = (240.0 * kotlin.math.PI / 180).toFloat()
                    val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
                    val t = Offset(-sin(a), cos(a))
                    val n = Offset(cos(a), sin(a))
                    val k = s * 0.2f
                    val head = Path().apply {
                        moveTo(p.x + t.x * k, p.y + t.y * k)
                        lineTo(p.x + n.x * k * 0.8f, p.y + n.y * k * 0.8f)
                        lineTo(p.x - n.x * k * 0.8f, p.y - n.y * k * 0.8f)
                        close()
                    }
                    drawPath(head, ink)
                }
                PlayerIcon.SETTINGS -> {
                    // Три вертикальные черты (заказчик 2026-10-24: шестерёнка читалась как «яркость»).
                    for (k in 0..2) {
                        val x = s * (0.2f + 0.3f * k)
                        drawLine(ink, Offset(x, s * 0.12f), Offset(x, s * 0.88f), w, StrokeCap.Round)
                    }
                }
                PlayerIcon.CLOSE -> {
                    val e = s * 0.18f
                    drawLine(ink, Offset(e, e), Offset(s - e, s - e), w, StrokeCap.Round)
                    drawLine(ink, Offset(s - e, e), Offset(e, s - e), w, StrokeCap.Round)
                }
                PlayerIcon.FULL, PlayerIcon.FULL_EXIT -> {
                    val e = s * 0.34f
                    val out = icon == PlayerIcon.FULL
                    for ((cx, cy) in listOf(0f to 0f, s to 0f, 0f to s, s to s)) {
                        val dx = if (cx == 0f) 1f else -1f
                        val dy = if (cy == 0f) 1f else -1f
                        // Наружу — уголок в углу; внутрь — уголок отодвинут к середине и смотрит наружу.
                        val corner = if (out) Offset(cx, cy) else Offset(cx + dx * e, cy + dy * e)
                        val hx = if (out) dx * e else -dx * e
                        val hy = if (out) dy * e else -dy * e
                        drawLine(ink, corner, Offset(corner.x + hx, corner.y), w, StrokeCap.Round)
                        drawLine(ink, corner, Offset(corner.x, corner.y + hy), w, StrokeCap.Round)
                    }
                }
            }
        }
    }
}

/** Метки в окне (§3.6): текущая подсвечена по ходу просмотра, нажатие перематывает; чужой ролик — блёклые. */
@Composable
private fun PlayerMarks(
    marks: List<io.tima.domain.chat.MediaMark>,
    positionMs: Long,
    enabled: Boolean,
    maxHeight: androidx.compose.ui.unit.Dp = 180.dp,
    onSeek: (Long) -> Unit,
) {
    val colors = Tima.colors
    val current = if (enabled) currentMark(marks, positionMs) else -1
    // Размер — как у меток в сообщении (заказчик 2026-10-24): главная строка сообщений, по их группе.
    ProvidePlace(TextPlace.MESSAGES) {
    Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).alpha(if (enabled) 1f else 0.4f).testTag("player:marks"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((i, m) in marks.withIndex()) {
            Row(
                Modifier.fillMaxWidth().background(if (i == current) colors.navigation.copy(alpha = 0.35f) else colors.viewer, RoundedCornerShape(6.dp))
                    .clickable(enabled = enabled) { onSeek(m.atMs) }.padding(horizontal = 6.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption(io.tima.domain.chat.markTime(m.atMs), fontSize = TimaType.sz4, weight = FontWeight.SemiBold, color = colors.link)
                Caption(m.text, fontSize = TimaType.sz4, color = io.tima.core.ui.TimaFixed.onVideo, modifier = Modifier.weight(1f))
            }
        }
    }
    }
}

/**
 * Полоса проигрывания: нажатие — перемотка к доле [onSeek]. Перетаскивание: без [onDrag] — перемотка по
 * ходу; с ним — по ходу только [onDrag] (подсказка), перемотка — когда отпустили, и [onDrag] `null`.
 */
@Composable
internal fun SeekBar(
    progress: Float,
    modifier: Modifier,
    marks: List<Float> = emptyList(),
    track: Color = Tima.colors.line,
    onDrag: ((Float?) -> Unit)? = null,
    onSeek: (Float) -> Unit,
) {
    val colors = Tima.colors
    // Жесты живут дольше одного показа — зовут последний [onSeek], а не первый: после смены движка
    // перемотка уходила бы в прежний.
    val seekNow by androidx.compose.runtime.rememberUpdatedState(onSeek)
    val dragNow by androidx.compose.runtime.rememberUpdatedState(onDrag)
    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { o -> seekNow((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                var last = 0f
                fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        last = at(o.x)
                        dragNow?.invoke(last)
                    },
                    onDragEnd = {
                        dragNow?.let {
                            seekNow(last)
                            it(null)
                        }
                    },
                    onDragCancel = { dragNow?.invoke(null) },
                ) { c, _ ->
                    last = at(c.position.x)
                    dragNow?.invoke(last) ?: seekNow(last)
                }
            },
    ) {
        val h = 4.dp.toPx()
        val y = (size.height - h) / 2
        drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2, h / 2))
        drawRoundRect(colors.navigation, Offset(0f, y), Size(size.width * progress.coerceIn(0f, 1f), h), CornerRadius(h / 2, h / 2))
        // Отрезки по меткам (Р12): тонкий зазор на месте каждой метки.
        for (m in marks) drawRect(colors.viewer, Offset(size.width * m - 1.dp.toPx(), y), Size(2.dp.toPx(), h))
        drawCircle(io.tima.core.ui.TimaFixed.onVideo, 7.dp.toPx(), Offset(size.width * progress.coerceIn(0f, 1f), size.height / 2))
    }
}
