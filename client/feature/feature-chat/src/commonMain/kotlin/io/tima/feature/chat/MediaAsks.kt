package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.tima.core.media.OwnPlayerSurface
import io.tima.core.media.rememberOwnPlayer
import io.tima.core.ui.TextPlace
import io.tima.core.ui.ProvidePlace
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.Name
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedMedia
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.MediaLinks
import io.tima.domain.chat.MediaMark
import io.tima.domain.chat.OutMedia
import io.tima.domain.chat.markParts
import io.tima.domain.chat.markTimeOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Что за ссылка на поток и сколько длится запись (Р29, Р34–Р36): узнаёт отправитель при вставке. */
data class MediaProbe(val kind: MediaLinks.Kind, val durationMs: Long = 0)

/**
 * Ссылка в форме «Видео», которая в окне TIMA не играет (страница автора, канал, обычный сайт), —
 * кнопкой-ссылкой с названием из «Описания»: вид, картинка и метки ей ни к чему (заказчик 2026-10-23).
 */
internal fun videoAskLink(url: String, description: String): ComposedWidget.Link =
    ComposedWidget.Link(url, title = description.trim(), mode = "button")

/**
 * «Облако» с видом «Видео» или «Аудио» (заказчик 2026-10-24): ссылка, которая играет в окне TIMA, —
 * нашим виджетом «Видео» или «Аудио», описание — из «Названия», пусто — имя файла (Р27). Не играет
 * (страница диска, архив) или вид другой — `null`: остаётся файл в облаке, как был.
 */
fun cloudMediaWidget(url: String, title: String, kind: String?, doc: ComposedDoc): ComposedWidget? {
    val k = MediaLinks.kindOf(url) ?: return null
    val description = ComposedMedia.unique(doc, title.trim().ifBlank { MediaLinks.fileName(url) })
    return when {
        kind == "video" && k != MediaLinks.Kind.AUDIO_FILE -> ComposedWidget.Video(url, description)
        kind == "audio" && k == MediaLinks.Kind.AUDIO_FILE -> ComposedWidget.Audio(url, description)
        else -> null
    }
}

/**
 * Что по ссылке — словами, после ухода с поля (заказчик 2026-10-24): видео сайта, видео- или звуковой файл,
 * поток; не играет, а по окончанию файл — вид файла («По ссылке — файл: Картинка»). Страница — `null`: о ней
 * говорит подсказка «не является видео».
 */
fun linkKindText(url: String, ew: io.tima.core.words.EditorWords, cw: io.tima.core.words.ChatWords): String? {
    val ext = io.tima.domain.chat.FileKinds.extOf(io.tima.domain.chat.FileKinds.nameOf(url))
    return when (MediaLinks.kindOf(url)) {
        MediaLinks.Kind.YOUTUBE -> ew.linkPlays("youtube", ext)
        MediaLinks.Kind.RUTUBE -> ew.linkPlays("rutube", ext)
        MediaLinks.Kind.VK_VIDEO -> ew.linkPlays("vk", ext)
        MediaLinks.Kind.VIDEO_FILE -> ew.linkPlays("video", ext)
        MediaLinks.Kind.AUDIO_FILE -> ew.linkPlays("audio", ext)
        MediaLinks.Kind.STREAM -> ew.linkPlays("stream", ext)
        null -> if (io.tima.domain.chat.FileKinds.isFile(url, null)) {
            cw.linkFileIs(cw.fileKindName(io.tima.domain.chat.FileKinds.ofName(io.tima.domain.chat.FileKinds.nameOf(url)).key))
        } else null
    }
}

/**
 * Что уходит из скрепки «Из облака» (заказчик 2026-10-24, 1в#): видео или звук, что играют в окне TIMA, —
 * нашим виджетом «Видео» или «Аудио», без меток; файл и не играющее — «файлом в облаке» с видом. Название
 * пусто — имя файла из ссылки.
 */
fun cloudAttachWidget(url: String, title: String, kind: String): ComposedWidget {
    val clean = url.trim().let { if ("://" in it) it else "https://$it" }
    val media = if (kind == "video" || kind == "audio") cloudMediaWidget(clean, title, kind, ComposedDoc(emptyList())) else null
    return media ?: ComposedWidget.Cloud(clean, kind, title.trim().ifBlank { io.tima.domain.chat.FileKinds.nameOf(clean) })
}

/** Что форме вставки нужно от ведущего редактор: сеть и загрузка картинки. */
class MediaAskPorts(
    /** m3u8: видео, запись или эфир и длительность записи; не узнали — `null`. */
    val probe: suspend (url: String) -> MediaProbe? = { null },
    /** Выбрать и загрузить свою картинку видео (Р43); ответ — в колбэке, `null` — не вышло. */
    val pickPicture: ((OutMedia?) -> Unit) -> Unit = { it(null) },
)

object MediaAskTags {
    const val URL = "media.ask.url"
    const val DESCRIPTION = "media.ask.description"
    const val COUNT = "media.ask.count"
    const val OK = "media.ask.ok"
    const val MARK_AT_NOW = "media.ask.mark.now"
    const val NOT_VIDEO = "media.ask.notvideo"
    const val DELETE = "media.ask.delete"
    const val HIDDEN = "media.ask.hidden"
    /** Что по ссылке — после ухода с поля (заказчик 2026-10-24). */
    const val LINK_KIND = "media.ask.kind"
    fun markHour(i: Int) = "media.ask.mark.$i.hour"
    fun markMinute(i: Int) = "media.ask.mark.$i.minute"
    fun markSecond(i: Int) = "media.ask.mark.$i.second"
    fun markText(i: Int) = "media.ask.mark.$i.text"
    fun markDelete(i: Int) = "media.ask.mark.$i.delete"
}

/**
 * Вставка «Видео», «Аудио» или «Потока» (ПЛАН-(ВМ) §3.6–§3.8) кнопкой «Виджет». Список ссылок в поле
 * (из буфера) — каждая своим виджетом, описание — имя файла (Р27). Метки к видео — сразу: «Сколько
 * меток» > 0 открывает форму меток ([onMarks]).
 */
@Composable
fun MediaWidgetAsk(
    kind: String,
    doc: ComposedDoc,
    ports: MediaAskPorts,
    onInsert: (List<ComposedWidget>) -> Unit,
    onMarks: (bind: String, count: Int) -> Unit,
    onClose: () -> Unit,
) {
    val ew = Tima.words.editor
    val cw = Tima.words.chat
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var look by remember { mutableStateOf("picture") }
    var showTitle by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf<OutMedia?>(null) }
    var count by remember { mutableStateOf("0") }
    var trouble by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notVideo by remember { mutableStateOf(false) }
    var linkSays by remember { mutableStateOf<String?>(null) }
    val links = ComposedMedia.splitLinks(url)
    val list = kind != "stream" && links.size > 1
    val left = 30 - ComposedMedia.count(doc)
    val prober = rememberOwnPlayer()

    /** Длительность звука — читает начало файла своим движком, без звука (Р29). */
    suspend fun durationOf(u: String): Long {
        if (MediaLinks.kindOf(u) == MediaLinks.Kind.STREAM) return ports.probe(u)?.durationMs ?: 0
        val p = prober ?: return 0
        p.open(u, play = false)
        return withTimeoutOrNull(8_000) {
            while (p.state.value.durationMs <= 0 && p.state.value.error == null) delay(100)
            p.state.value.durationMs
        } ?: 0
    }

    /** Какой виджет у ссылки: по окончанию, у m3u8 — по содержимому (Р34–Р36). */
    suspend fun kindOf(u: String): MediaLinks.Kind? {
        val k = MediaLinks.kindOf(u)
        return if (k == MediaLinks.Kind.STREAM) ports.probe(u)?.kind ?: k else k
    }

    fun insertList() {
        busy = true
        scope.launch {
            val out = mutableListOf<ComposedWidget>()
            var skipped = 0
            val taken = mutableListOf<String>()
            for (u in links) {
                if (out.size >= left) {
                    skipped++
                    continue
                }
                val name = ComposedMedia.unique(doc, MediaLinks.fileName(u), taken)
                when (kindOf(u)) {
                    MediaLinks.Kind.AUDIO_FILE -> out += ComposedWidget.Audio(u, name, durationOf(u))
                    MediaLinks.Kind.VIDEO_FILE, MediaLinks.Kind.YOUTUBE, MediaLinks.Kind.RUTUBE, MediaLinks.Kind.VK_VIDEO -> out += ComposedWidget.Video(u, name)
                    MediaLinks.Kind.STREAM -> out += ComposedWidget.Stream(u, name)
                    null -> {
                        skipped++
                        continue
                    }
                }
                taken += name
            }
            busy = false
            onInsert(out)
            trouble = if (skipped > 0) ew.skipped(skipped) else null
            if (skipped == 0) onClose()
        }
    }

    fun normalized() = url.trim().let { if (it.isEmpty() || "://" in it) it else "https://$it" }

    fun insertOne() {
        val u = normalized()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return run { trouble = ew.needUrl }
        if (description.isBlank()) return run { trouble = ew.needDescription }
        if (left <= 0) return run { trouble = ew.tooMany }
        busy = true
        trouble = ew.probing
        scope.launch {
            val k = kindOf(u)
            val desc = ComposedMedia.unique(doc, description)
            busy = false
            trouble = null
            when (kind) {
                "video" -> when (k) {
                    MediaLinks.Kind.AUDIO_FILE -> trouble = ew.isAudio
                    MediaLinks.Kind.STREAM -> trouble = ew.isStream
                    null -> {
                        onInsert(listOf(videoAskLink(u, description)))
                        onClose()
                    }
                    else -> {
                        onInsert(listOf(ComposedWidget.Video(u, desc, look, showTitle, picture = picture)))
                        val n = count.toIntOrNull()?.coerceIn(0, 30) ?: 0
                        if (n > 0) onMarks(desc, n) else onClose()
                    }
                }
                "audio" -> when (k) {
                    MediaLinks.Kind.AUDIO_FILE -> {
                        onInsert(listOf(ComposedWidget.Audio(u, desc, durationOf(u))))
                        val n = count.toIntOrNull()?.coerceIn(0, 30) ?: 0
                        if (n > 0) onMarks(desc, n) else onClose()
                    }
                    MediaLinks.Kind.STREAM -> trouble = ew.isStream
                    MediaLinks.Kind.VIDEO_FILE -> trouble = ew.isVideo
                    else -> trouble = ew.isService
                }
                else -> when (k) {
                    MediaLinks.Kind.STREAM -> {
                        onInsert(listOf(ComposedWidget.Stream(u, desc)))
                        onClose()
                    }
                    MediaLinks.Kind.AUDIO_FILE -> trouble = ew.isAudio
                    MediaLinks.Kind.VIDEO_FILE -> trouble = ew.isVideo
                    else -> trouble = ew.notStream
                }
            }
        }
    }

    SheetFrame(
        title = when (kind) {
            "video" -> ew.askVideo
            "audio" -> ew.askAudio
            else -> ew.askStream
        },
        onClose = onClose,
    ) {
        Field(
            ew.askUrl, url, MediaAskTags.URL, single = false,
            // Ушли с поля — ссылка, которая в окне TIMA не играет, видна сразу, а не по «Готово» (заказчик 2026-10-23).
            onLeave = {
                notVideo = kind == "video" && !list && url.isNotBlank() && !MediaLinks.playsHere(normalized())
                linkSays = if (list || url.isBlank()) null else linkKindText(normalized(), ew, cw)
            },
        ) { url = it; trouble = null; notVideo = false; linkSays = null }
        // Что по ссылке — видео, звук, поток, картинка или другой файл (заказчик 2026-10-24).
        linkSays?.let { Tertiary(it, modifier = Modifier.testTag(MediaAskTags.LINK_KIND)) }
        if (notVideo) Caption(ew.notVideo, fontSize = TimaType.sz5, color = Tima.colors.alarm, modifier = Modifier.testTag(MediaAskTags.NOT_VIDEO))
        if (list) {
            Tertiary(ew.listFound(links.size))
        } else {
            Field(ew.askDescription, description, MediaAskTags.DESCRIPTION) { description = it; trouble = null }
            // Вид, своя картинка и метки — только у видео: кнопке-ссылке они ни к чему.
            if (kind == "video" && !notVideo) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip(ew.lookPicture, kind = if (look == "picture") ChipKind.Selected else ChipKind.Quiet, onClick = { look = "picture" })
                    Chip(ew.lookFrame, kind = if (look == "frame") ChipKind.Selected else ChipKind.Quiet, onClick = { look = "frame" })
                    Chip(ew.showTitle, kind = if (showTitle) ChipKind.Selected else ChipKind.Quiet, onClick = { showTitle = !showTitle })
                }
                Chip(if (picture != null) ew.ownPictureChosen else ew.ownPicture, kind = if (picture != null) ChipKind.Selected else ChipKind.Quiet, onClick = {
                    ports.pickPicture { picture = it }
                })
            }
            if (kind != "stream" && !notVideo) Field(ew.marksCount, count, MediaAskTags.COUNT) { count = it.filter(Char::isDigit).take(2) }
        }
        trouble?.let { Caption(it, fontSize = TimaType.sz5, color = Tima.colors.alarm) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(Tima.words.chat.ok, kind = ChipKind.Selected, onClick = { if (!busy) { if (list) insertList() else insertOne() } }, modifier = Modifier.testTag(MediaAskTags.OK))
            Chip(Tima.words.chat.cancel, kind = ChipKind.Quiet, onClick = onClose)
        }
    }
}

/** Строка окна меток: время тремя полями — час, минута, секунда (заказчик 2026-10-24) — и текст. */
private data class MarkRow(val h: String = "", val m: String = "", val s: String = "", val text: String = "") {
    /** Ни одно поле времени не заполнено — строка красная. */
    val empty get() = h.isBlank() && m.isBlank() && s.isBlank()

    companion object {
        fun at(ms: Long, text: String = "") = markParts(ms).let { (h, m, s) -> MarkRow(h, m, s, text) }
    }
}

/**
 * Метки к видео или аудио этого сообщения (§3.6): строки «час · мин · сек — текст», «+Метка», убрать
 * строку — круглый «✕»; маленький проигрыватель привязанного и «Метка на текущей секунде». До 30. Время
 * — три поля (заказчик 2026-10-24): заполнено любое — пустые нули; ни одно — строка красная, сохранить
 * нельзя. Окно без единой метки — как без меток: «Готово» просит добавить, «Удалить» убирает метки к
 * видео. «Не показывать в сообщении» — метки только в проигрывателе. При сохранении встают по времени.
 */
@Composable
fun MarksAsk(
    doc: ComposedDoc,
    bind: String?,
    count: Int,
    onSave: (ComposedWidget.Marks) -> Unit,
    onClose: () -> Unit,
    /** «Удалить» — убрать метки к этому видео или аудио. */
    onDelete: (bind: String) -> Unit = {},
) {
    val ew = Tima.words.editor
    val colors = Tima.colors
    val targets = ComposedMedia.bindable(doc)
    var chosen by remember { mutableStateOf(bind ?: targets.firstOrNull()) }
    val existing = chosen?.let { ComposedMedia.marksOf(doc, it) }
    var rows by remember(chosen) {
        mutableStateOf(
            existing?.marks?.map { MarkRow.at(it.atMs, it.text) }
                ?: List(count.coerceIn(1, 30)) { MarkRow() },
        )
    }
    var hidden by remember(chosen) { mutableStateOf(existing?.hidden ?: false) }
    var trouble by remember { mutableStateOf<String?>(null) }
    val target = chosen?.let { ComposedMedia.target(doc, it) }
    val targetUrl = when (target) {
        is ComposedWidget.Video -> target.url
        is ComposedWidget.Audio -> target.url
        else -> null
    }
    val player = if (targetUrl != null && MediaLinks.playsHere(targetUrl)) rememberOwnPlayer() else null
    LaunchedEffect(player, targetUrl) { if (player != null && targetUrl != null) player.open(targetUrl, play = false) }
    val state = player?.state?.collectAsState()
    fun change(i: Int, row: MarkRow) {
        rows = rows.toMutableList().also { it[i] = row }
        trouble = null
    }
    SheetFrame(ew.askMarks, onClose) {
        if (targets.isEmpty()) {
            Tertiary(ew.marksNoTarget)
            return@SheetFrame
        }
        Tertiary(ew.marksFor, lineOne = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            for (t in targets.take(6)) Chip(t, kind = if (t == chosen) ChipKind.Selected else ChipKind.Quiet, onClick = { chosen = t })
        }
        if (player != null && state != null) {
            val s = state.value
            if (target is ComposedWidget.Video) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9).background(colors.viewer, RoundedCornerShape(8.dp))) {
                    OwnPlayerSurface(player, Modifier.fillMaxSize())
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip(if (s.playing) "❚❚" else "▶", kind = ChipKind.Quiet, onClick = { if (s.playing) player.pause() else player.play() })
                Caption(voiceTime(s.positionMs), fontSize = TimaType.sz4)
                Chip(ew.marksAtNow, kind = ChipKind.Selected, modifier = Modifier.testTag(MediaAskTags.MARK_AT_NOW), onClick = {
                    if (rows.size >= 30) trouble = ew.marksMax else rows = rows + MarkRow.at(s.positionMs)
                })
            }
            if (s.durationMs > 0) SeekBar(s.positionMs.toFloat() / s.durationMs, Modifier.fillMaxWidth().heightIn(min = 20.dp)) { f -> player.seek((s.durationMs * f).toLong()) }
        }
        // Подписи полей времени — над строками, в самих полях только серый «0».
        if (rows.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (label in listOf(ew.markHour, ew.markMinute, ew.markSecond)) Box(Modifier.width(TIME_FIELD)) { Tertiary(label, lineOne = true) }
                Tertiary(ew.marksText, lineOne = true)
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((i, row) in rows.withIndex()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    for ((part, tag) in listOf(0 to MediaAskTags.markHour(i), 1 to MediaAskTags.markMinute(i), 2 to MediaAskTags.markSecond(i))) {
                        val value = when (part) { 0 -> row.h; 1 -> row.m; else -> row.s }
                        Box(Modifier.width(TIME_FIELD)) {
                            Field(null, value, tag, alarm = row.empty, hint = "0", digits = true) { v ->
                                val d = v.filter(Char::isDigit).take(4)
                                change(i, when (part) { 0 -> row.copy(h = d); 1 -> row.copy(m = d); else -> row.copy(s = d) })
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        Field(null, row.text, MediaAskTags.markText(i)) { v -> change(i, row.copy(text = v.take(200))) }
                    }
                    // Убрать строку — круглым пузырём, как «+Метка»: знак без круга на белом был не виден.
                    Box(
                        Modifier.size(32.dp).background(colors.softAccent, CircleShape)
                            .clickable { rows = rows.filterIndexed { j, _ -> j != i }; trouble = null }
                            .testTag(MediaAskTags.markDelete(i)),
                        contentAlignment = Alignment.Center,
                    ) { Caption("✕", fontSize = TimaType.sz5, weight = FontWeight.Bold, color = colors.text2) }
                }
            }
        }
        Chip(ew.marksAdd, kind = ChipKind.Quiet, onClick = { if (rows.size >= 30) trouble = ew.marksMax else rows = rows + MarkRow() })
        Chip(ew.marksHidden, kind = if (hidden) ChipKind.Selected else ChipKind.Quiet, onClick = { hidden = !hidden },
            modifier = Modifier.testTag(MediaAskTags.HIDDEN))
        trouble?.let { Caption(it, fontSize = TimaType.sz5, color = colors.alarm) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(Tima.words.chat.ok, kind = ChipKind.Selected, modifier = Modifier.testTag(MediaAskTags.OK), onClick = {
                val b = chosen ?: return@Chip
                val marks = rows.map { r -> markTimeOf(r.h, r.m, r.s)?.let { MediaMark(it, r.text.trim()) } }
                when {
                    rows.isEmpty() -> trouble = ew.marksNeed
                    marks.any { it == null } -> trouble = ew.marksBadTime
                    rows.size > 30 -> trouble = ew.marksMax
                    else -> {
                        onSave(ComposedWidget.Marks(b, marks.filterNotNull().sortedBy { it.atMs }, hidden))
                        onClose()
                    }
                }
            })
            Chip(Tima.words.chat.cancel, kind = ChipKind.Quiet, onClick = onClose)
            Chip(ew.marksDelete, kind = ChipKind.Quiet, modifier = Modifier.testTag(MediaAskTags.DELETE), onClick = {
                chosen?.let(onDelete)
                onClose()
            })
        }
    }
}

/** Ширина поля часа, минуты и секунды — на четыре цифры. */
private val TIME_FIELD = 48.dp

/**
 * Подокно снизу, как у других вопросов редактора: затемнение, нажатие мимо закрывает. Заголовок — по
 * «Шапкам», поля и кнопки — по «Меню и настройкам» (заказчик 2026-10-24).
 */
@Composable
private fun SheetFrame(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val colors = Tima.colors
    ProvidePlace(TextPlace.MENU) {
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().background(colors.surface).clickable(enabled = false) {}
                .padding(TimaSpacing.about4).verticalScroll(rememberScrollState()).testTag("media.ask"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProvidePlace(TextPlace.HEADERS) { Name(title) }
            content()
        }
    }
    }
}

@Composable
private fun Field(
    label: String?,
    value: String,
    tag: String,
    single: Boolean = true,
    alarm: Boolean = false,
    onLeave: (() -> Unit)? = null,
    /** Серая подсказка в пустом поле. */
    hint: String? = null,
    /** Только цифры — цифровая клавиатура. */
    digits: Boolean = false,
    onChange: (String) -> Unit,
) {
    val colors = Tima.colors
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) Tertiary(label, lineOne = true)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = single,
            maxLines = if (single) 1 else 4,
            textStyle = TextStyle(fontSize = TimaType.sz4, color = if (alarm && value.isNotBlank()) colors.alarm else colors.text),
            cursorBrush = SolidColor(colors.navigation),
            keyboardOptions = if (digits) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            decorationBox = { inner ->
                Box {
                    if (hint != null && value.isEmpty()) Caption(hint, fontSize = TimaType.sz4, color = colors.text3)
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth().background(if (alarm) colors.alarm.copy(alpha = 0.12f) else colors.functional, RoundedCornerShape(8.dp))
                .padding(10.dp).testTag(tag)
                .onFocusChanged { if (it.isFocused) focused = true else if (focused) { focused = false; onLeave?.invoke() } },
        )
    }
}
