package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import io.tima.domain.chat.MediaLinks
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.IconButton
import io.tima.core.ui.ListLine
import io.tima.core.ui.Name
import io.tima.core.ui.TimaZones
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaSpacing
import io.tima.core.ui.TimaType
import io.tima.core.ui.words

/** Метки ссылок (СЦЕНАРИИ-ПО-ОКНАМ, «Переписка»). */
object LinkTags {
    /** «Перейти» под абзацем. */
    const val GO = "msg:link.go"
    const val ASK = "msg:link.ask"
    const val ASK_OPEN = "msg:link.ask.open"
    const val ASK_CANCEL = "msg:link.ask.cancel"
    const val ASK_COPY = "msg:link.ask.copy"
}

/**
 * Что спрашивает окно перехода (заказчик 2026-10-20: «все кликабельные — через окно перехода… если
 * окно откроется в редакторе, переход заблокирован»): ссылка или сообщение. [blocked] — окно то же,
 * «Перейти» недоступно.
 */
sealed interface GateAsk {
    val blocked: Boolean

    data class Link(val url: String, override val blocked: Boolean = false) : GateAsk

    /** Переход к сообщению: кто и что в нём; [go] — сам переход. */
    data class Message(val name: String?, val preview: String?, val go: () -> Unit, override val blocked: Boolean = false) : GateAsk
}

/**
 * Открыть видео или звук в окне проигрывания (ПЛАН-(ВМ) §3.1) — ставит [LinkGate]: файлы и потоки —
 * в окне TIMA, сайты — пока браузером (ВМ6–ВМ8). `null` — окна нет (снимки, проверки).
 */
val LocalPlay = staticCompositionLocalOf<((PlayRequest) -> Unit)?> { null }

/** Окно перехода над всем приложением — ставит [LinkGate]; нет его (снимки, проверки) — переход сразу. */
val LocalGate = staticCompositionLocalOf<((GateAsk) -> Unit)?> { null }

/** Здесь переход заблокирован — редактор (заказчик 2026-10-20): окно показывается, «Перейти» — нет. */
val LocalGateBlocked = staticCompositionLocalOf { false }

/**
 * Открыть ссылку из сообщения. Всякая ссылка — сначала через окно с доменом и вопросом (заказчик
 * 2026-10-19: «Показывает домен и спрашивает. Для всех ссылок»). Прежний вход — для мест без окна.
 */
val LocalLinkOpen = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Как открыть ссылку здесь: через окно перехода, а нет его — сразу. */
@Composable
internal fun rememberLinkOpen(): (String) -> Unit {
    val gate = LocalGate.current
    val blocked = LocalGateBlocked.current
    val old = LocalLinkOpen.current
    val uri = LocalUriHandler.current
    return when {
        gate != null -> { url -> gate(GateAsk.Link(url, blocked)) }
        old != null -> old
        else -> { url -> runCatching { uri.openUri(url) } }
    }
}

/**
 * Как перейти к сообщению здесь: через окно перехода — автор и строка из сообщения; нет окна —
 * сразу. Сам переход — [LocalOpenMessage] переписки.
 */
@Composable
internal fun rememberMessageOpen(): (kind: String, chatId: String, messageId: Long) -> Unit {
    val gate = LocalGate.current
    val blocked = LocalGateBlocked.current
    val look = LocalQuoteLook.current
    val open = LocalOpenMessage.current
    return { kind, chat, id ->
        if (gate == null) {
            open(kind, chat, id)
        } else {
            val l = look(kind, chat, id)
            gate(GateAsk.Message(l.name, l.text, { open(kind, chat, id) }, blocked))
        }
    }
}

/**
 * Окно перехода поверх всего приложения: у ссылки — домен крупно (его не подделать длинным путём) и
 * адрес целиком; у сообщения — автор и строка. «Перейти» переходит, «✕» — нет.
 */
@Composable
fun LinkGate(
    /** Строка журнала окна проигрывания (`PLAYER`). */
    onPlayer: (what: PlayerEvent, detail: String) -> Unit = { _, _ -> },
    content: @Composable () -> Unit,
) {
    var asked by remember { mutableStateOf<GateAsk?>(null) }
    // Видео или звук прямой ссылкой — своё окно проигрывания, а не браузер (ПЛАН-(ВМ) §3.1).
    var playing by remember { mutableStateOf<PlayRequest?>(null) }
    val uri = LocalUriHandler.current
    CompositionLocalProvider(
        LocalGate provides { ask -> asked = ask },
        LocalLinkOpen provides { url -> asked = GateAsk.Link(url) },
        LocalPlay provides { req ->
            if (MediaLinks.playsHere(req.url)) playing = req else runCatching { uri.openUri(req.url) }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            content()
            when (val ask = asked) {
                is GateAsk.Link -> LinkAsk(
                    url = ask.url,
                    blocked = ask.blocked,
                    onOpen = {
                        asked = null
                        if (MediaLinks.playsHere(ask.url)) {
                            playing = PlayRequest(ask.url, startMs = (MediaLinks.startSeconds(ask.url) ?: 0) * 1000L)
                        } else {
                            runCatching { uri.openUri(ask.url) }
                        }
                    },
                    onCancel = { asked = null },
                )
                is GateAsk.Message -> MessageAsk(
                    ask,
                    onOpen = {
                        asked = null
                        ask.go()
                    },
                    onCancel = { asked = null },
                )
                null -> Unit
            }
            playing?.let { req ->
                PlayerWindow(
                    request = req,
                    onClose = { playing = null },
                    onBrowser = { url ->
                        playing = null
                        runCatching { uri.openUri(url) }
                    },
                    onEvent = onPlayer,
                )
            }
        }
    }
}

/**
 * Окно ссылки — как меню сообщения (заказчик 2026-10-19): сверху домен крупно и «✕» справа, под ним
 * адрес целиком — только здесь он и виден; ниже строки «Перейти» и «Скопировать ссылку». В редакторе
 * «Перейти» недоступно.
 */
@Composable
private fun LinkAsk(url: String, blocked: Boolean, onOpen: () -> Unit, onCancel: () -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.chat
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    GateSheet(onCancel) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tertiary(w.linkAskOpen, lineOne = true)
                Caption(hostOf(url), fontSize = TimaType.sz2, weight = FontWeight.ExtraBold, color = colors.link)
                Caption(url, fontSize = TimaType.sz5, color = colors.text2)
                if (url.startsWith("http://")) Caption(Tima.words.editor.unsafeLink, fontSize = TimaType.sz5, color = colors.alarm)
            }
            IconButton(glyph = "✕", onClick = onCancel, modifier = Modifier.testTag(LinkTags.ASK_CANCEL))
        }
        GoItem(blocked, LinkTags.ASK_OPEN, onOpen)
        LinkItem(w.linkCopy, LinkTags.ASK_COPY) {
            clipboard.setText(AnnotatedString(url))
            onCancel()
        }
    }
}

/** Окно перехода к сообщению: автор, строка из сообщения, «Перейти» (в редакторе — недоступно), «✕». */
@Composable
private fun MessageAsk(ask: GateAsk.Message, onOpen: () -> Unit, onCancel: () -> Unit) {
    val w = Tima.words.chat
    GateSheet(onCancel) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TimaSpacing.about4, vertical = TimaSpacing.about2),
            horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about3),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tertiary(w.messageAsk, lineOne = true)
                ask.name?.let { Caption(it, fontSize = TimaType.sz3, weight = FontWeight.ExtraBold) }
                Caption(ask.preview ?: w.quotedElsewhere, fontSize = TimaType.sz5, color = Tima.colors.text2, maxLines = 3)
            }
            IconButton(glyph = "✕", onClick = onCancel, modifier = Modifier.testTag(LinkTags.ASK_CANCEL))
        }
        GoItem(ask.blocked, LinkTags.ASK_OPEN, onOpen)
    }
}

/** Подложка окна перехода: тень на всё, снизу — лист. */
@Composable
private fun GateSheet(onCancel: () -> Unit, content: @Composable () -> Unit) {
    val colors = Tima.colors
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = TimaZones.zone1).background(colors.surface).clickable(enabled = false) {}
                .testTag(LinkTags.ASK),
        ) { content() }
    }
}

/** «Перейти»; в редакторе — серой строкой «переход недоступен». */
@Composable
private fun GoItem(blocked: Boolean, tag: String, onOpen: () -> Unit) {
    val w = Tima.words.chat
    if (blocked) {
        ListLine(
            modifier = Modifier.testTag(tag),
            middle = { Tertiary(w.gateBlocked, lineOne = true) },
        )
    } else {
        LinkItem(w.linkGo, tag, onOpen)
    }
}

@Composable
private fun LinkItem(title: String, tag: String, onClick: () -> Unit) = ListLine(
    modifier = Modifier.testTag(tag),
    onClick = onClick,
    middle = { Name(title) },
    right = { Tertiary("›", lineOne = true) },
)

/** Синий пузырь «Перейти» — цвет ссылок из «Цветов». */
@Composable
internal fun LinkPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Tima.colors
    Caption(
        label,
        fontSize = TimaType.sz5,
        weight = FontWeight.Bold,
        color = colors.onAccent,
        lineOne = true,
        modifier = modifier.background(colors.link, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Подпись пузыря ссылки: «Перейти» (или название) и домен, длинный домен — с обрезкой. */
internal fun linkLabel(go: String, url: String): String {
    val host = hostOf(url)
    return "$go · " + if (host.length > MAX_HOST) host.take(MAX_HOST - 1) + "…" else host
}

private const val MAX_HOST = 24

/**
 * Ссылки абзаца — пузырями «Перейти · домен» под ним (заказчик 2026-10-19): сам адрес в тексте не
 * показывается, полный — только в окне перехода.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinksUnder(urls: List<String>) {
    if (urls.isEmpty()) return
    val open = rememberLinkOpen()
    val w = Tima.words.chat
    FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (url in urls.distinct()) {
            LinkPill(linkLabel(w.linkGo, url), Modifier.testTag(LinkTags.GO)) { open(url) }
        }
    }
}

/**
 * Текст без вставленных адресов: адрес заменяется пузырём под абзацем. Лишние пробелы на месте
 * адреса сжимаются; оформление и метки сдвигаются вслед.
 */
internal fun withoutLinks(text: io.tima.domain.chat.RichText): io.tima.domain.chat.RichText {
    val cuts = text.spans.filter { it.kind == io.tima.domain.chat.SpanKind.LINK }
        .map { it.start.coerceIn(0, text.text.length) until it.end.coerceIn(0, text.text.length) }
    return cut(text, cuts)
}

private fun cut(text: io.tima.domain.chat.RichText, ranges: List<IntRange>): io.tima.domain.chat.RichText {
    if (ranges.isEmpty()) return text
    val gone = BooleanArray(text.text.length)
    for (r in ranges) for (i in r) if (i in gone.indices) gone[i] = true
    // Пробел перед вырезанным уходит вместе с ним — «сайт https://x.ru» → «сайт».
    for (r in ranges) {
        val before = r.first - 1
        val after = r.last + 1
        if (before >= 0 && text.text[before] == ' ' && (after >= text.text.length || text.text[after] == ' ' || text.text[after] == '\n')) gone[before] = true
    }
    val map = IntArray(text.text.length + 1)
    val out = StringBuilder()
    for (i in text.text.indices) {
        map[i] = out.length
        if (!gone[i]) out.append(text.text[i])
    }
    map[text.text.length] = out.length
    val spans = text.spans.filter { it.kind != io.tima.domain.chat.SpanKind.LINK }.mapNotNull { sp ->
        val a = map[sp.start.coerceIn(0, text.text.length)]
        val b = map[sp.end.coerceIn(0, text.text.length)]
        if (b > a) sp.copy(start = a, end = b) else null
    }
    val lead = out.length - out.trimStart().length
    val shown = out.toString().trim()
    return io.tima.domain.chat.RichText(shown, spans.mapNotNull { sp ->
        val a = (sp.start - lead).coerceIn(0, shown.length)
        val b = (sp.end - lead).coerceIn(0, shown.length)
        if (b > a) sp.copy(start = a, end = b) else null
    })
}

/** Адреса в простом тексте — одна грамматика с показом и редактором ([io.tima.domain.chat.TextLinks], ADR-0050 §3). */
internal fun urlsIn(text: String): List<IntRange> = io.tima.domain.chat.TextLinks.find(text)

/** Адрес для открытия: без схемы — с `https://`. */
internal fun openable(url: String): String = io.tima.domain.chat.TextLinks.openable(url)

/** Ссылка в тексте: цвет ссылок и нажатие — через [rememberLinkOpen]. */
internal fun AnnotatedString.Builder.linkAt(start: Int, end: Int, url: String, color: androidx.compose.ui.graphics.Color, open: (String) -> Unit) {
    addLink(
        LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.SemiBold))) { open(url) },
        start,
        end,
    )
}

/** Простое сообщение без оформления: адреса в нём заменены пузырями «Перейти · домен» под текстом. */
@Composable
internal fun PlainWithLinks(text: String) {
    val ranges = remember(text) { urlsIn(text) }
    if (ranges.isEmpty()) {
        Caption(text, fontSize = TimaType.sz4)
        return
    }
    val rest = remember(text) { cut(io.tima.domain.chat.RichText(text), ranges).text }
    Column {
        if (rest.isNotBlank()) Caption(rest, fontSize = TimaType.sz4)
        LinksUnder(ranges.map { openable(text.substring(it)) })
    }
}
