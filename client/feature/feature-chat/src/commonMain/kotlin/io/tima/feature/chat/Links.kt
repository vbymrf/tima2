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
 * Открыть ссылку из сообщения. Всякая ссылка — сначала через окно с доменом и вопросом (заказчик
 * 2026-10-19: «Показывает домен и спрашивает. Для всех ссылок»); окно ставит [LinkGate]. Без него
 * (снимки экранов, проверки) — сразу.
 */
val LocalLinkOpen = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Как открыть ссылку здесь: через окно приложения, а нет его — сразу. */
@Composable
internal fun rememberLinkOpen(): (String) -> Unit {
    val gate = LocalLinkOpen.current
    val uri = LocalUriHandler.current
    return gate ?: { url -> runCatching { uri.openUri(url) } }
}

/**
 * Окно «Перейти по ссылке?» поверх всего приложения: домен крупно (его не подделать длинным
 * путём), ниже адрес целиком; «Перейти» открывает, «Отмена» — нет.
 */
@Composable
fun LinkGate(content: @Composable () -> Unit) {
    var asked by remember { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    CompositionLocalProvider(LocalLinkOpen provides { url -> asked = url }) {
        Box(Modifier.fillMaxSize()) {
            content()
            asked?.let { url ->
                LinkAsk(
                    url = url,
                    onOpen = {
                        asked = null
                        runCatching { uri.openUri(url) }
                    },
                    onCancel = { asked = null },
                )
            }
        }
    }
}

/**
 * Окно ссылки — как меню сообщения (заказчик 2026-10-19): сверху домен крупно и «✕» справа, под ним
 * адрес целиком — только здесь он и виден; ниже строки «Перейти» и «Скопировать ссылку».
 */
@Composable
private fun LinkAsk(url: String, onOpen: () -> Unit, onCancel: () -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.chat
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = TimaZones.zone1).background(colors.surface).clickable(enabled = false) {}
                .testTag(LinkTags.ASK),
        ) {
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
            LinkItem(w.linkGo, LinkTags.ASK_OPEN, onOpen)
            LinkItem(w.linkCopy, LinkTags.ASK_COPY) {
                clipboard.setText(AnnotatedString(url))
                onCancel()
            }
        }
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
