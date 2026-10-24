package io.tima.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.OwnPlayer
import io.tima.core.media.decodeImage
import io.tima.core.media.mediaUri
import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.MessagePicture
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.MediaLinks
import io.tima.domain.chat.MediaMark
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.markTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Название и картинка со страницы видео (Р7, Р16) — загружает устройство получателя. */
class MediaCard(val title: String, val image: ByteArray?)

/** Что умеет экран для видео и звука по ссылке (ПЛАН-(ВМ) §3.5–§3.8). */
class MediaActions(
    /** Название и картинка со страницы; `null` — не загружаем (снимки, проверки). */
    val card: (suspend (url: String) -> MediaCard?)? = null,
    /** Проигрыватель звука в сообщении — один на приложение. */
    val audio: AudioHub? = null,
)

val LocalMediaActions = staticCompositionLocalOf { MediaActions() }

/** Блоки всего сообщения — метки ищут своё видео или аудио по описанию (Р10). */
internal val LocalRichBlocks = staticCompositionLocalOf<List<RichBlock>> { emptyList() }

object MediaTags {
    const val VIDEO = "msg:video"
    const val AUDIO = "msg:audio"
    const val AUDIO_PLAY = "msg:audio.play"
    const val STREAM = "msg:stream"
    const val MARKS = "msg:marks"
    fun mark(i: Int) = "msg:mark.$i"
}

/** Видео или аудио по описанию — для меток (Р10); ищется по всему сообщению, в рядах тоже. */
internal fun List<RichBlock>.mediaBy(description: String): RichBlock? {
    for (b in this) {
        when (b) {
            is RichBlock.Video -> if (b.description == description) return b
            is RichBlock.Audio -> if (b.description == description) return b
            is RichBlock.Row -> for (c in b.cells) (c.blocks + c.widgets).mediaBy(description)?.let { return it }
            else -> Unit
        }
    }
    return null
}

/** Метки к видео или аудио с описанием [description]. */
internal fun List<RichBlock>.marksOf(description: String): List<MediaMark> {
    for (b in this) {
        when (b) {
            is RichBlock.Marks -> if (b.bind == description) return b.marks
            is RichBlock.Row -> for (c in b.cells) (c.blocks + c.widgets).marksOf(description).takeIf { it.isNotEmpty() }?.let { return it }
            else -> Unit
        }
    }
    return emptyList()
}

/** Все «Аудио» сообщения по порядку — следующее начинается само (Р28). */
internal fun List<RichBlock>.audios(): List<RichBlock.Audio> = flatMap { b ->
    when (b) {
        is RichBlock.Audio -> listOf(b)
        is RichBlock.Row -> b.cells.flatMap { (it.blocks + it.widgets).audios() }
        else -> emptyList()
    }
}

/**
 * Виджет «Видео» (ПЛАН-(ВМ) §3.6, Р43): картинка со страницы (загружает получатель, когда сообщение
 * видно), своя картинка отправителя, если у сайта нет, рамка — когда картинка не загрузилась.
 * Нажатие — окно проигрывания с метками этого видео.
 */
@Composable
internal fun VideoWidget(video: RichBlock.Video) {
    val colors = Tima.colors
    val actions = LocalMediaActions.current
    val play = LocalPlay.current
    val blocks = LocalRichBlocks.current
    val own = LocalRichMedia.current.getOrNull(video.picture)
    var card by remember(video.url) { mutableStateOf<MediaCard?>(null) }
    var picture by remember(video.url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(video.url, video.look) {
        if (video.look == "frame" && !video.showTitle) return@LaunchedEffect
        val got = actions.card?.invoke(video.url) ?: return@LaunchedEffect
        card = got
        if (video.look != "frame") picture = got.image?.let { runCatching { decodeImage(it) }.getOrNull() }
    }
    val host = MediaLinks.hostOf(video.url).orEmpty()
    val go = {
        play?.invoke(PlayRequest(video.url, video.description.ifBlank { card?.title.orEmpty() }, marks = blocks.marksOf(video.description)))
        Unit
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(colors.functional).clickable { go() }.testTag(MediaTags.VIDEO),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).background(colors.viewer), contentAlignment = Alignment.Center) {
            when {
                own != null && video.look != "frame" ->
                    MessagePicture(mediaUri(own.mediaId, own.key), 16f / 9, Tima.words.chat.pictureMissing, Modifier.fillMaxSize())
                picture != null -> Image(picture!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                // Рамка — домен крупно: картинки нет или не загрузилась (Р43).
                else -> Caption(host, fontSize = 18.sp, weight = FontWeight.Bold, color = io.tima.core.ui.TimaFixed.onVideo, lineOne = true)
            }
            Box(Modifier.size(52.dp).background(colors.navigation, CircleShape), contentAlignment = Alignment.Center) {
                Caption("▶", fontSize = 22.sp, color = colors.onAccent)
            }
        }
        Column(Modifier.padding(horizontal = 8.dp).padding(bottom = 6.dp)) {
            val title = video.title.ifBlank { card?.title.orEmpty() }
            if (video.showTitle && title.isNotBlank()) Caption(title, fontSize = TimaType.sz4, weight = FontWeight.Bold, maxLines = 2)
            Caption(host, fontSize = TimaType.sz5, weight = FontWeight.SemiBold, color = colors.link, lineOne = true)
        }
    }
}

/**
 * Виджет «Метки» (§3.6): заглавие — описание видео, заголовком 3; время — цветом ссылок, нажатие —
 * окно с этой секунды (видео) или проигрывание с неё прямо в сообщении (аудио). У играющего аудио
 * текущая метка подсвечена. Справа синяя полоса, как у ссылки «Текстом» (заказчик 2026-10-24).
 * «Не показывать в сообщении» — не рисуется вовсе: метки только в проигрывателе.
 */
@Composable
internal fun MarksWidget(marks: RichBlock.Marks) {
    if (marks.hidden) return
    val colors = Tima.colors
    val play = LocalPlay.current
    val actions = LocalMediaActions.current
    val blocks = LocalRichBlocks.current
    val target = blocks.mediaBy(marks.bind)
    val from = LocalMessagePlace.current
    val nowFlow = actions.audio?.now ?: remember { MutableStateFlow<AudioNow?>(null) }
    val now by nowFlow.collectAsState()
    val playingHere = target is RichBlock.Audio && now?.url == target.url
    val current = if (playingHere) currentMark(marks.marks, now!!.positionMs) else -1
    Column(Modifier.fillMaxWidth().edgeStripe(linkStripe()).testTag(MediaTags.MARKS), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (marks.bind.isNotBlank()) Caption(marks.bind, fontSize = TimaType.heading3, weight = FontWeight.ExtraBold)
        for ((i, m) in marks.marks.withIndex()) {
            Row(
                Modifier.fillMaxWidth().background(if (i == current) colors.softAccent else colors.functional.copy(alpha = 0f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Caption(
                    markTime(m.atMs),
                    fontSize = TimaType.sz4,
                    weight = FontWeight.SemiBold,
                    color = colors.link,
                    modifier = Modifier.clickable(enabled = target != null) {
                        when (target) {
                            is RichBlock.Video -> play?.invoke(PlayRequest(target.url, target.description, m.atMs, marks.marks))
                            is RichBlock.Audio -> actions.audio?.playAt(target.item(from), blocks.audios().map { it.item(from) }, m.atMs)
                            else -> Unit
                        }
                    }.testTag(MediaTags.mark(i)),
                )
                Caption(m.text, fontSize = TimaType.sz4, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Блок, в котором видно только метки «Не показывать в сообщении», — в пузыре ему нет места. */
internal fun RichBlock.hiddenOnly(): Boolean = when (this) {
    is RichBlock.Marks -> hidden
    is RichBlock.Row -> cells.isNotEmpty() && cells.all { c ->
        c.text?.text.isNullOrEmpty() && (c.blocks + c.widgets).let { inside -> inside.isNotEmpty() && inside.all { it.hiddenOnly() } }
    }
    else -> false
}

/**
 * Дуга, бегущая вокруг круглой кнопки ▶ (заказчик 2026-10-23 — у видео, 2026-10-24 — у голосового и «Аудио»):
 * [active] — крутится, нет — не рисуется. Рисуется за краем кнопки, на [gap] от него.
 */
@Composable
internal fun Modifier.spinArc(active: Boolean, color: androidx.compose.ui.graphics.Color, gap: androidx.compose.ui.unit.Dp = 4.dp): Modifier {
    if (!active) return this
    val turn = rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1000, easing = androidx.compose.animation.core.LinearEasing)), label = "spin",
    ).value
    return this.drawWithContent {
        drawContent()
        val g = gap.toPx()
        drawArc(
            color, startAngle = turn - 90f, sweepAngle = 100f, useCenter = false,
            topLeft = Offset(-g, -g), size = Size(size.width + g * 2, size.height + g * 2),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

/** Номер текущей метки на [positionMs]; до первой — -1. */
internal fun currentMark(marks: List<MediaMark>, positionMs: Long): Int = marks.indexOfLast { it.atMs <= positionMs }

internal fun RichBlock.Audio.item(from: MessagePlace? = null) = AudioItem(url, description, durationMs, live = false, from = from)
internal fun RichBlock.Stream.item(from: MessagePlace? = null) = AudioItem(url, description, 0, live = true, from = from)

/** Сообщение, где стоит виджет: переписка и номер — к нему ведёт полоса «сейчас играет». */
data class MessagePlace(val chatId: String, val messageId: Long)

/** Сообщение, которое сейчас рисуется в ленте; вне ленты (редактор, окно одного сообщения) — `null`. */
internal val LocalMessagePlace = staticCompositionLocalOf<MessagePlace?> { null }

/**
 * Виджет «Аудио» (§3.7, Р38): ▶, описание и длительность, под ними полоса — нажатие и перетаскивание
 * перематывают; скорость — только пока играет; недоступный — серым.
 */
@Composable
internal fun AudioWidget(audio: RichBlock.Audio) {
    val colors = Tima.colors
    val hub = LocalMediaActions.current.audio
    val blocks = LocalRichBlocks.current
    val nowFlow = hub?.now ?: remember { MutableStateFlow<AudioNow?>(null) }
    val now by nowFlow.collectAsState()
    val mine = now?.takeIf { it.url == audio.url }
    val duration = mine?.durationMs?.takeIf { it > 0 } ?: audio.durationMs
    val marks = blocks.marksOf(audio.description)
    val from = LocalMessagePlace.current
    Row(
        Modifier.fillMaxWidth().background(colors.functional, RoundedCornerShape(18.dp)).padding(8.dp).testTag(MediaTags.AUDIO),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Дуга бежит, пока грузится и играет (заказчик 2026-10-24); на паузе — стоит.
        val spinning = mine != null && !mine.failed && (mine.playing || !mine.started || mine.buffering)
        PlayCircle(mine?.playing == true, enabled = hub != null && mine?.failed != true, tag = MediaTags.AUDIO_PLAY, spinning = spinning) {
            hub?.toggle(audio.item(from), blocks.audios().map { it.item(from) })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Caption(audio.description.ifBlank { MediaLinks.fileName(audio.url) }, fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true, modifier = Modifier.weight(1f))
                Tertiary(
                    when {
                        mine?.failed == true -> Tima.words.chat.mediaUnavailable
                        mine != null && (mine.playing || mine.positionMs > 0) -> voiceTime(mine.positionMs)
                        duration > 0 -> voiceTime(duration)
                        else -> "—:—"
                    },
                    lineOne = true,
                )
            }
            val progress = if (mine != null && duration > 0) mine.positionMs.toFloat() / duration else 0f
            SeekBar(progress, Modifier.fillMaxWidth().height(20.dp), marks = if (duration > 0) marks.map { it.atMs.toFloat() / duration } else emptyList()) { f ->
                hub?.seek(audio.item(from), blocks.audios().map { it.item(from) }, f)
            }
            if (mine != null && mine.playing && hub != null) {
                Chip(speedName(mine.speed), kind = ChipKind.Quiet, onClick = { hub.cycleSpeed() })
            }
        }
    }
}

/**
 * Виджет «Поток» (§3.8, Р38): ▶/⏸, описание, «● В эфире», строка «Сейчас: …», живой столбик и время
 * прослушивания. Полосы, длительности, скорости и меток нет; пауза, потом ▶ — с эфира (Р35).
 */
@Composable
internal fun StreamWidget(stream: RichBlock.Stream) {
    val colors = Tima.colors
    val words = Tima.words.chat
    val hub = LocalMediaActions.current.audio
    val nowFlow = hub?.now ?: remember { MutableStateFlow<AudioNow?>(null) }
    val now by nowFlow.collectAsState()
    val mine = now?.takeIf { it.url == stream.url }
    val from = LocalMessagePlace.current
    Row(
        Modifier.fillMaxWidth().background(colors.functional, RoundedCornerShape(18.dp)).padding(8.dp).testTag(MediaTags.STREAM),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayCircle(
            mine?.playing == true, enabled = hub != null, tag = "msg:stream.play",
            spinning = mine != null && !mine.failed && (mine.playing || !mine.started || mine.buffering),
        ) { hub?.toggle(stream.item(from), emptyList()) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption(stream.description.ifBlank { MediaLinks.hostOf(stream.url).orEmpty() }, fontSize = TimaType.sz4, weight = FontWeight.Bold, lineOne = true, modifier = Modifier.weight(1f))
                when {
                    mine?.failed == true -> Tertiary(words.streamUnavailable, lineOne = true)
                    mine?.playing == true && mine.started -> Caption(words.playerLive, fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.alarm)
                    mine?.playing == true -> Tertiary(words.streamConnecting, lineOne = true)
                }
            }
            mine?.title?.takeIf { it.isNotBlank() }?.let { Tertiary(words.streamNow(it), lineOne = true) }
            if (mine?.playing == true) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LiveBars(Modifier.width(40.dp).height(14.dp))
                    Tertiary(words.streamListening(voiceTime(mine.listenedMs)), lineOne = true)
                }
            }
        }
    }
}

@Composable
private fun PlayCircle(playing: Boolean, enabled: Boolean, tag: String, spinning: Boolean = false, onClick: () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.size(40.dp).spinArc(spinning, colors.navigation).background(if (enabled) colors.navigation else colors.line, CircleShape)
            .clickable(enabled = enabled, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Caption(if (playing) "❚❚" else "▶", fontSize = 16.sp, color = colors.onAccent)
    }
}

/** Столбик «идёт звук» у эфира: громкости поток не отдаёт — столбики качаются, пока играет. */
@Composable
private fun LiveBars(modifier: Modifier) {
    val color = Tima.colors.navigation
    val t = rememberInfiniteTransition()
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    Canvas(modifier) {
        val n = 5
        val step = size.width / n
        val w = step * 0.6f
        for (i in 0 until n) {
            val k = ((phase + i * 0.37f) % 1f) * 0.8f + 0.2f
            val h = size.height * k
            drawRoundRect(color, Offset(i * step, size.height - h), Size(w, h), CornerRadius(w / 2, w / 2))
        }
    }
}

internal fun speedName(speed: Float) = when (speed) {
    1.5f -> "1,5×"
    2f -> "2×"
    else -> "1×"
}

/**
 * Полоса «сейчас играет» (ПЛАН-(ВМ) Р22): «Аудио» или «Поток» играет — над любым окном приложения,
 * и в переписке, и на списке чатов: ▶/⏸, описание, ✕ — остановить; нажатие на полосу — к сообщению, где
 * запущено. Крупно, по «Шапкам» «Шрифтов и
 * размеров» (заказчик 2026-10-24): описание — главной строкой шапки, ▶ и ✕ — кругами, как в окне видео.
 */
@Composable
fun NowPlayingBar(hub: AudioHub?) {
    hub ?: return
    val now by hub.now.collectAsState()
    val n = now ?: return
    val colors = Tima.colors
    ProvidePlace(TextPlace.HEADERS) {
    Row(
        // Над окном, под строкой состояния телефона.
        // Нажатие на полосу — к сообщению, где запущено (заказчик 2026-10-24); ▶ и ✕ — свои.
        Modifier.fillMaxWidth().background(colors.softAccent).statusBarsPadding()
            .then(
                hub.current?.from?.let { place -> hub.onOpenPlace?.let { open -> Modifier.clickable { open(place) } } } ?: Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("media:now"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(colors.navigation, CircleShape)
                .clickable { hub.toggle(hub.current ?: AudioItem(n.url, hub.description, n.durationMs, n.live), hub.queueNow) },
            contentAlignment = Alignment.Center,
        ) { Caption(if (n.playing) "❚❚" else "▶", fontSize = TimaType.sz3, color = colors.onAccent) }
        Caption(hub.description.ifBlank { MediaLinks.fileName(n.url) }, fontSize = TimaType.sz3, weight = FontWeight.Bold, lineOne = true, modifier = Modifier.weight(1f))
        if (n.live && n.playing) Caption(Tima.words.chat.playerLive, fontSize = TimaType.sz4, color = colors.alarm)
        else if (!n.live) Caption(voiceTime(n.positionMs), fontSize = TimaType.sz4, color = colors.text2, lineOne = true)
        Box(
            Modifier.size(40.dp).background(colors.functional, CircleShape).clickable { hub.close() }.testTag("media:now.close"),
            contentAlignment = Alignment.Center,
        ) { Caption("✕", fontSize = TimaType.sz3, weight = FontWeight.Bold, color = colors.text2) }
    }
    }
}

/** Звук по ссылке — что играть: адрес, описание, длительность, эфир ли и из какого сообщения запущен. */
data class AudioItem(val url: String, val description: String, val durationMs: Long, val live: Boolean, val from: MessagePlace? = null)

/** Что играет из «Аудио» и «Потока» сейчас (§3.7, §3.8). */
data class AudioNow(
    val url: String,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val live: Boolean = false,
    val started: Boolean = false,
    /** Ждёт данных посреди проигрывания — дуга вокруг ▶. */
    val buffering: Boolean = false,
    val failed: Boolean = false,
    /** Эфир: название песни, если поток его сообщает. */
    val title: String? = null,
    /** Эфир: сколько слушают, мс. */
    val listenedMs: Long = 0,
)

/**
 * Проигрыватель «Аудио» и «Потока» — один на приложение (§3.7): играет прямо в сообщении и дальше,
 * когда ушли из переписки (Р22); новое ставит прежнее на паузу, голосовые — тоже ([onStart]).
 * Кончилось «Аудио» — само начинается следующее «Аудио» этого сообщения (Р28). Эфир после паузы —
 * с эфира, а не с места паузы (Р35): открывается заново.
 */
class AudioHub(private val scope: CoroutineScope, private val player: OwnPlayer) {
    private val _now = MutableStateFlow<AudioNow?>(null)
    val now: StateFlow<AudioNow?> = _now.asStateFlow()

    /** Началось проигрывание — остановить прочее (голосовые, окно видео). */
    var onStart: (() -> Unit)? = null

    /** Строка журнала: что, сайт. */
    var onEvent: ((what: PlayerEvent, host: String) -> Unit)? = null

    private var item: AudioItem? = null
    private var queue: List<AudioItem> = emptyList()

    /** Описание играющего — для полосы «сейчас играет». */
    val description: String get() = item?.description.orEmpty()

    /** Очередь играющего — для ▶ на полосе. */
    val queueNow: List<AudioItem> get() = queue

    /** Играющее — для ▶ на полосе: с ним и сообщение, откуда запущено. */
    val current: AudioItem? get() = item

    /** Нажали полосу «сейчас играет» — к сообщению, где запущено (заказчик 2026-10-24). Ставит окно. */
    var onOpenPlace: ((MessagePlace) -> Unit)? = null
    private var watch: Job? = null
    private var listenedFrom: kotlin.time.TimeMark? = null
    private var speed = 1f

    init {
        watch = scope.launch {
            player.state.collect { s ->
                val it = item ?: return@collect
                if (s.error != null && _now.value?.failed != true) onEvent?.invoke(PlayerEvent.FAILED, MediaLinks.hostOf(it.url).orEmpty())
                _now.value = AudioNow(
                    url = it.url,
                    playing = s.playing,
                    positionMs = s.positionMs,
                    durationMs = if (it.live) 0 else s.durationMs.takeIf { d -> d > 0 } ?: it.durationMs,
                    speed = s.speed,
                    live = it.live,
                    started = s.started,
                    buffering = s.buffering,
                    failed = s.error != null,
                    title = s.title,
                    listenedMs = listenedFrom?.takeIf { s.playing }?.elapsedNow()?.inWholeMilliseconds ?: _now.value?.listenedMs ?: 0,
                )
                if (s.ended && !it.live) advance(it)
            }
        }
    }

    fun toggle(next: AudioItem, all: List<AudioItem>) {
        val cur = item
        val s = player.state.value
        if (cur?.url == next.url && s.error == null) {
            if (s.playing) {
                player.pause()
                return
            }
            // Эфир после паузы — с эфира (Р35): открыть заново.
            if (cur.live) start(next, all, 0) else player.play()
            onStart?.invoke()
            return
        }
        start(next, all, 0)
    }

    fun playAt(next: AudioItem, all: List<AudioItem>, ms: Long) {
        if (item?.url == next.url && player.state.value.error == null) {
            player.seek(ms)
            player.play()
            onStart?.invoke()
        } else {
            start(next, all, ms)
        }
    }

    fun seek(next: AudioItem, all: List<AudioItem>, fraction: Float) {
        val d = (if (item?.url == next.url) player.state.value.durationMs else 0).takeIf { it > 0 } ?: next.durationMs
        if (d <= 0) return
        playAt(next, all, (d * fraction).toLong())
    }

    /** 1× → 1,5× → 2× → 1×, тон сохраняется (Р39). */
    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        player.setSpeed(speed)
    }

    fun stop() {
        if (item != null && player.state.value.playing) player.pause()
    }

    /** Закрыть «сейчас играет»: остановить и забыть, служба фона — не нужна. */
    fun close() {
        player.pause()
        item = null
        _now.value = null
        io.tima.core.media.keepPlaying(player, false)
    }

    private fun start(next: AudioItem, all: List<AudioItem>, fromMs: Long) {
        onStart?.invoke()
        item = next
        queue = all
        listenedFrom = kotlin.time.TimeSource.Monotonic.markNow()
        _now.value = AudioNow(next.url, durationMs = next.durationMs, live = next.live, speed = speed)
        player.open(next.url, fromMs)
        if (!next.live) player.setSpeed(speed) else player.setSpeed(1f)
        // Звук и после ухода из переписки, при погашенном экране (Р22).
        io.tima.core.media.keepPlaying(player, true)
        onEvent?.invoke(PlayerEvent.OPENED, MediaLinks.hostOf(next.url).orEmpty())
    }

    /** Кончилось — следующее «Аудио» этого сообщения (Р28); после последнего — стоп. */
    private fun advance(done: AudioItem) {
        val at = queue.indexOfFirst { it.url == done.url }
        val next = queue.getOrNull(at + 1) ?: return io.tima.core.media.keepPlaying(player, false)
        if (at < 0) return
        start(next, queue, 0)
    }
}
