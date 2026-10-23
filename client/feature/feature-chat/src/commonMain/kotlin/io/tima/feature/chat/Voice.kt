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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.OggOpus
import io.tima.core.media.VOICE_MAX_MS
import io.tima.core.media.VOICE_WARN_MS
import io.tima.core.media.VoiceClip
import io.tima.core.media.VoiceDevices
import io.tima.core.media.VoicePlayback
import io.tima.core.media.VoiceRecording
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.RichMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Что играет сейчас (ПЛАН-(ВМ) §2.4): одно на всё приложение. */
data class VoiceNow(
    val mediaId: String,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    /** Файл ещё едет или расшифровывается. */
    val loading: Boolean = false,
    /** Не вышло: файла нет, колонки недоступны. */
    val failed: Boolean = false,
)

/**
 * Проигрыватель голосовых — один на всё приложение (ПЛАН-(ВМ) §3.7: «одновременно играет что-то
 * одно»). Новое ставит прежнее на паузу. Кончилось — само начинается следующее голосовое
 * переписки, до первого сообщения без голосового (Р40): кого играть следующим, говорит [next].
 *
 * [load] — расшифрованный файл Ogg Opus (папка, иначе сервер); на диск открытым он не ложится.
 */
class VoiceHub(
    private val scope: CoroutineScope,
    private val load: suspend (RichMedia) -> ByteArray?,
) {
    private val _now = MutableStateFlow<VoiceNow?>(null)
    val now: StateFlow<VoiceNow?> = _now.asStateFlow()

    /** Следующее голосовое после [mediaId] в открытой переписке; `null` — дальше не голосовое. */
    var next: ((mediaId: String) -> RichMedia?)? = null

    /** Началось проигрывание — остановить прочее («Аудио», «Поток»): одно на всё приложение. */
    var onStart: (() -> Unit)? = null

    /** Строка журнала: что случилось, с каким файлом; слова журнала — у того, кто пишет (`shared`). */
    var onEvent: ((what: VoiceEvent, mediaId: String, seconds: Long) -> Unit)? = null

    private var current: RichMedia? = null
    private var playback: VoicePlayback? = null
    private var watch: Job? = null
    private var speed = 1f

    fun toggle(m: RichMedia) {
        val p = playback
        if (current?.mediaId == m.mediaId && p != null) {
            if (p.state.value.playing) p.pause() else p.play()
            return
        }
        start(m, 0)
    }

    /** Перемотка по доле волны [fraction]; не это голосовое — начать его с этого места. */
    fun seek(m: RichMedia, fraction: Float) {
        val p = playback
        val f = fraction.coerceIn(0f, 1f)
        if (current?.mediaId == m.mediaId && p != null) p.seek((p.state.value.durationMs * f).toLong())
        else start(m, (m.durationMs * f).toLong())
    }

    /** 1× → 1,5× → 2× → 1× (Р39: тон сохраняется). */
    fun cycleSpeed() {
        speed = when (speed) {
            1f -> 1.5f
            1.5f -> 2f
            else -> 1f
        }
        playback?.setSpeed(speed)
        _now.value = _now.value?.copy(speed = speed)
    }

    /** Остановить всё — начинается другое проигрывание или запись. */
    fun stop() {
        watch?.cancel()
        watch = null
        playback?.release()
        playback = null
        _now.value = _now.value?.copy(playing = false)
    }

    private fun start(m: RichMedia, fromMs: Long) {
        stop()
        onStart?.invoke()
        current = m
        _now.value = VoiceNow(m.mediaId, durationMs = m.durationMs, speed = speed, loading = true)
        watch = scope.launch {
            val stream = load(m)?.let(OggOpus::read)
            if (stream == null) {
                _now.value = VoiceNow(m.mediaId, durationMs = m.durationMs, speed = speed, failed = true)
                onEvent?.invoke(VoiceEvent.OPEN_FAILED, m.mediaId, 0)
                return@launch
            }
            val p = VoicePlayback(stream, scope)
            playback = p
            p.setSpeed(speed)
            if (fromMs > 0) p.seek(fromMs)
            p.onEnded = { scope.launch { advance(m) } }
            onEvent?.invoke(VoiceEvent.PLAYING, m.mediaId, fromMs / 1000)
            p.play()
            p.state.collect { s ->
                _now.value = VoiceNow(m.mediaId, s.playing, s.positionMs, s.durationMs, s.speed, failed = s.failed)
            }
        }
    }

    private fun advance(after: RichMedia) {
        if (current?.mediaId != after.mediaId) return
        val n = next?.invoke(after.mediaId)
        if (n == null) {
            onEvent?.invoke(VoiceEvent.ENDED, after.mediaId, after.durationMs / 1000)
            return
        }
        onEvent?.invoke(VoiceEvent.ENDED_NEXT, after.mediaId, after.durationMs / 1000)
        start(n, 0)
    }
}

/** Что случилось с голосовым — для журнала (`VOICE`); слова — у пишущего журнал. */
enum class VoiceEvent {
    /** Проигрывание: файла нет или не открылся. */
    OPEN_FAILED,
    PLAYING,
    /** Доиграло, дальше не голосовое. */
    ENDED,
    /** Доиграло, началось следующее (Р40). */
    ENDED_NEXT,
    /** Запись: секунды — частота микрофона в кГц. */
    RECORD_STARTED,
    NO_MICROPHONE,
    NO_CODEC,
    RECORD_STOPPED,
    RECORD_DELETED,
}

/** Время `м:сс`. */
internal fun voiceTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

private fun speedLabel(speed: Float) = when (speed) {
    1.5f -> "1,5×"
    2f -> "2×"
    else -> "1×"
}

const val VOICE_TAG = "msg:voice"
const val VOICE_PLAY_TAG = "msg:voice.play"

/**
 * Голосовое вне пузыря (ПЛАН-(ВМ) §2.4): ▶/пауза, волна с бегунком — нажатие и перетаскивание
 * перематывают, время, скорость — пока играет. Срок вышел — «Голосовое недоступно», длительность
 * и волна остаются: они в сообщении.
 */
@Composable
internal fun VoiceView(m: RichMedia) {
    val actions = LocalAttachmentActions.current
    val hub = actions.voice
    val colors = Tima.colors
    val now by (hub?.now ?: remember { MutableStateFlow<VoiceNow?>(null) }).collectAsState()
    val mine = now?.takeIf { it.mediaId == m.mediaId }
    val gone = actions.gone(m.mediaId)
    val duration = mine?.durationMs?.takeIf { it > 0 } ?: m.durationMs
    val progress = if (mine != null && duration > 0) mine.positionMs.toFloat() / duration else 0f
    Row(
        Modifier.widthIn(min = 220.dp, max = 280.dp).background(colors.functional, RoundedCornerShape(18.dp))
            .padding(8.dp).testTag(VOICE_TAG),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(if (gone) colors.line else colors.navigation, CircleShape)
                .clickable(enabled = !gone && hub != null) { hub?.toggle(m) }.testTag(VOICE_PLAY_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Caption(if (mine?.playing == true) "❚❚" else "▶", fontSize = 16.sp, color = colors.onAccent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            VoiceWaveBars(
                m.wave, progress, colors.navigation, colors.text3,
                Modifier.fillMaxWidth().height(28.dp).let { mod ->
                    if (gone || hub == null) mod else mod
                        .pointerInput(m.mediaId) { detectTapGestures { o -> hub.seek(m, o.x / size.width) } }
                        .pointerInput(m.mediaId) {
                            detectHorizontalDragGestures { change, _ -> hub.seek(m, change.position.x / size.width) }
                        }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    gone -> Tertiary(Tima.words.chat.voiceUnavailable, lineOne = true)
                    mine?.failed == true -> Tertiary(Tima.words.chat.voiceFailed, lineOne = true)
                    else -> Tertiary(voiceTime(if (mine != null && (mine.playing || mine.positionMs > 0)) mine.positionMs else duration), lineOne = true)
                }
                if (mine != null && !gone && hub != null) {
                    Chip(speedLabel(mine.speed), kind = ChipKind.Quiet, onClick = { hub.cycleSpeed() }, modifier = Modifier.testTag("msg:voice.speed"))
                }
            }
        }
    }
}

/** Волна голосового: доли громкости столбиками; сыгранное — цветом [played]. */
@Composable
internal fun VoiceWaveBars(wave: ByteArray, progress: Float, played: Color, rest: Color, modifier: Modifier) {
    val bars = if (wave.isEmpty()) ByteArray(io.tima.core.media.VoiceWave.BARS) { 40 } else wave
    Canvas(modifier) {
        val n = bars.size
        val step = size.width / n
        val w = (step * 0.6f).coerceAtLeast(1f)
        val minH = 2.dp.toPx()
        for (i in 0 until n) {
            val v = (bars[i].toInt() and 0xFF) / 255f
            val h = (size.height * v).coerceAtLeast(minH)
            drawRoundRect(
                color = if ((i + 0.5f) / n <= progress) played else rest,
                topLeft = Offset(i * step + (step - w) / 2, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/**
 * Голосовое из строки ввода (ПЛАН-(ВМ) §2.5): кнопка справа — микрофон, пока не введён ни один
 * символ. Даёт переписка: разрешение спрашивает окно, микрофон ПК — выбранный для звонков (Р42).
 */
class VoiceInput(
    /** Спросить микрофон; ответ — в колбэке. */
    val ask: (onResult: (Boolean) -> Unit) -> Unit,
    /** Имя микрофона (ПК: выбранный для звонков); `null` — по умолчанию. */
    val micName: () -> String? = { null },
    /** Отправить записанное. */
    val send: (VoiceClip) -> Unit,
    /** Строка журнала: что случилось и число (секунды; у начала записи — кГц). */
    val onEvent: (what: VoiceEvent, value: Long) -> Unit = { _, _ -> },
)

object VoiceTags {
    const val MIC = "chat:voice"
    const val AREA = "chat:voice.area"
    const val STOP = "chat:voice.stop"
    const val SEND = "chat:voice.send"
    const val DELETE = "chat:voice.delete"
    const val LISTEN = "chat:voice.listen"
}

/**
 * Область записи на месте клавиатуры (проба 05 `doc/Layout-UI-light/пробы-редактор/подокна.html`):
 * запись идёт сразу, крупно время и волна; корзина — удалить, красная — стоп и послушать, зелёная —
 * отправить. Крестик — удалить; длиннее 3 с — с вопросом. На 9:30 — предупреждение, на 10:00 запись
 * встаёт сама.
 */
@Composable
internal fun VoiceArea(voice: VoiceInput, onClose: () -> Unit) {
    val colors = Tima.colors
    val words = Tima.words.chat
    val scope = rememberCoroutineScope()
    val hub = LocalAttachmentActions.current.voice
    // Микрофон открывается сразу: нажали — пошла запись (Р33).
    val recording = remember {
        hub?.stop()
        val source = VoiceDevices.microphone(voice.micName())
        val encoder = source?.let { VoiceDevices.encoder(it.rate) }
        if (source != null && encoder != null) {
            voice.onEvent(VoiceEvent.RECORD_STARTED, source.rate / 1000L)
            VoiceRecording(source, encoder, scope)
        } else {
            source?.close()
            voice.onEvent(if (source == null) VoiceEvent.NO_MICROPHONE else VoiceEvent.NO_CODEC, 0)
            null
        }
    }
    var clip by remember { mutableStateOf<VoiceClip?>(null) }
    var preview by remember { mutableStateOf<VoicePlayback?>(null) }
    var asking by remember { mutableStateOf(false) }
    val elapsed by (recording?.elapsedMs ?: remember { MutableStateFlow(0L) }).collectAsState()
    val levels by (recording?.levels ?: remember { MutableStateFlow(emptyList<Float>()) }).collectAsState()
    val limit by (recording?.stoppedByLimit ?: remember { MutableStateFlow(false) }).collectAsState()
    val previewState = preview?.state?.collectAsState()
    DisposableEffect(recording) {
        onDispose {
            recording?.cancel()
            preview?.release()
        }
    }
    fun stop() {
        scope.launch {
            val c = recording?.stop() ?: return@launch
            clip = c
            preview = OggOpus.read(c.ogg)?.let { VoicePlayback(it, scope) }
            voice.onEvent(VoiceEvent.RECORD_STOPPED, c.durationMs / 1000)
        }
    }
    LaunchedEffect(limit) { if (limit && clip == null) stop() }
    fun discard() {
        voice.onEvent(VoiceEvent.RECORD_DELETED, (clip?.durationMs ?: elapsed) / 1000)
        onClose()
    }
    fun close() {
        if ((clip?.durationMs ?: elapsed) > 3000) asking = true else discard()
    }
    fun send() {
        scope.launch {
            val c = clip ?: recording?.stop() ?: return@launch
            preview?.release()
            voice.send(c)
            onClose()
        }
    }
    Column(
        Modifier.fillMaxWidth().background(colors.functional).padding(horizontal = TimaSpacing.about3, vertical = 6.dp).testTag(VoiceTags.AREA),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tertiary(words.voiceArea, lineOne = true, modifier = Modifier.weight(1f))
            io.tima.core.ui.IconButton(glyph = "✕", onClick = ::close, modifier = Modifier.testTag(InputTags.AREA_CLOSE))
        }
        if (recording == null) {
            Tertiary(words.voiceNoMic)
            return@Column
        }
        val shownMs = clip?.durationMs ?: elapsed
        val playing = previewState?.value?.playing == true
        Caption(
            voiceTime(if (clip != null && previewState != null && (playing || previewState.value.positionMs > 0)) previewState.value.positionMs else shownMs),
            fontSize = 22.sp, weight = FontWeight.ExtraBold,
        )
        if (clip == null) {
            LiveWave(levels, colors.navigation, Modifier.widthIn(max = 260.dp).fillMaxWidth().height(30.dp))
        } else {
            val c = clip!!
            val pos = previewState?.value?.positionMs ?: 0
            VoiceWaveBars(c.wave, if (c.durationMs > 0) pos.toFloat() / c.durationMs else 0f, colors.navigation, colors.text3, Modifier.widthIn(max = 260.dp).fillMaxWidth().height(30.dp))
        }
        if (asking) {
            Tertiary(words.voiceDeleteAsk)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Chip(words.voiceDelete, kind = ChipKind.Selected, onClick = ::discard, modifier = Modifier.testTag("chat:voice.ask.delete"))
                Chip(words.voiceKeep, kind = ChipKind.Quiet, onClick = { asking = false })
            }
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = ::close).testTag(VoiceTags.DELETE), contentAlignment = Alignment.Center) {
                Caption("🗑", fontSize = 22.sp)
            }
            if (clip == null) {
                // Красная — стоп: дальше послушать перед отправкой.
                Box(
                    Modifier.size(64.dp).background(colors.alarm, CircleShape).clickable { stop() }.testTag(VoiceTags.STOP),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(20.dp).background(colors.onAccent, RoundedCornerShape(4.dp)))
                }
            } else {
                Box(
                    Modifier.size(64.dp).background(colors.navigation, CircleShape)
                        .clickable { preview?.let { if (it.state.value.playing) it.pause() else it.play() } }.testTag(VoiceTags.LISTEN),
                    contentAlignment = Alignment.Center,
                ) {
                    Caption(if (playing) "❚❚" else "▶", fontSize = 22.sp, color = colors.onAccent)
                }
            }
            Box(
                Modifier.size(44.dp).background(colors.navigation, CircleShape).clickable { send() }.testTag(VoiceTags.SEND),
                contentAlignment = Alignment.Center,
            ) {
                Caption("➤", fontSize = 20.sp, color = colors.onAccent)
            }
        }
        Tertiary(
            when {
                clip != null -> words.voiceStopped
                elapsed >= VOICE_WARN_MS && elapsed < VOICE_MAX_MS -> words.voiceLimitSoon
                else -> words.voiceRecording
            },
        )
    }
}

/** Живая волна записи: последние куски, новые справа. */
@Composable
private fun LiveWave(levels: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = VoiceRecording.LIVE_BARS
        val step = size.width / n
        val w = (step * 0.5f).coerceAtLeast(1f)
        val minH = 2.dp.toPx()
        val shift = n - levels.size
        for ((i, v) in levels.withIndex()) {
            val h = (size.height * v).coerceAtLeast(minH)
            drawRoundRect(
                color = color,
                topLeft = Offset((i + shift) * step + (step - w) / 2, (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/**
 * Следующее голосовое после [mediaId] (Р40): сообщение сразу новее в переписке, если оно —
 * голосовое; иначе `null` — подряд кончилось. [lines] — новые первыми, как их отдаёт база.
 */
fun nextVoiceAfter(lines: List<io.tima.domain.chat.ChatLine>, mediaId: String): RichMedia? {
    val at = lines.indexOfFirst { l -> l.rich?.media?.any { it.mediaId == mediaId } == true }
    if (at <= 0) return null
    val rich = lines[at - 1].rich ?: return null
    val voice = rich.blocks.singleOrNull() as? io.tima.domain.chat.RichBlock.Voice ?: return null
    return rich.media.getOrNull(voice.media)
}
