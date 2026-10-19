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

@Composable
private fun LinkAsk(url: String, onOpen: () -> Unit, onCancel: () -> Unit) {
    val colors = Tima.colors
    val w = Tima.words.chat
    Box(
        Modifier.fillMaxSize().background(colors.text.copy(alpha = 0.45f)).clickable(onClick = onCancel),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().background(colors.surface).clickable(enabled = false) {}
                .padding(TimaSpacing.about4).testTag(LinkTags.ASK),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Tertiary(w.linkAskOpen, lineOne = true)
            Caption(hostOf(url), fontSize = TimaType.sz2, weight = FontWeight.ExtraBold, color = colors.link)
            Caption(url, fontSize = TimaType.sz5, color = colors.text2)
            if (url.startsWith("http://")) Caption(Tima.words.editor.unsafeLink, fontSize = TimaType.sz5, color = colors.alarm)
            Row(horizontalArrangement = Arrangement.spacedBy(TimaSpacing.about2)) {
                LinkPill(w.linkGo, Modifier.testTag(LinkTags.ASK_OPEN), onOpen)
                io.tima.core.ui.Chip(w.cancel, kind = io.tima.core.ui.ChipKind.Quiet, onClick = onCancel, modifier = Modifier.testTag(LinkTags.ASK_CANCEL))
            }
        }
    }
}

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

/**
 * «Перейти» под абзацем (заказчик 2026-10-19): по пузырю на каждую ссылку абзаца, с доменом — какой
 * сайт откроется, видно до нажатия.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LinksUnder(urls: List<String>) {
    if (urls.isEmpty()) return
    val open = rememberLinkOpen()
    val w = Tima.words.chat
    FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (url in urls.distinct()) {
            LinkPill("${w.linkGo} · ${hostOf(url)}", Modifier.testTag(LinkTags.GO)) { open(url) }
        }
    }
}

/** Адреса в простом тексте: `https://…`, `http://…`, `www.…` — те же, что узнаёт быстрое оформление. */
private val PLAIN_URL = Regex("""(https?://|www\.)[^\s<>"«»]+""")

/** Адрес с отрезанными знаками препинания в конце: точка после ссылки — конец предложения. */
internal fun urlsIn(text: String): List<IntRange> = PLAIN_URL.findAll(text).map { m ->
    var end = m.range.last
    while (end > m.range.first && text[end] in ".,;:!?)]}") end--
    m.range.first..end
}.toList()

/** Адрес для открытия: `www.` — с `https://`. */
internal fun openable(url: String): String = if (url.startsWith("www.")) "https://$url" else url

/** Ссылка в тексте: цвет ссылок и нажатие — через [rememberLinkOpen]. */
internal fun AnnotatedString.Builder.linkAt(start: Int, end: Int, url: String, color: androidx.compose.ui.graphics.Color, open: (String) -> Unit) {
    addLink(
        LinkAnnotation.Clickable(url, TextLinkStyles(SpanStyle(color = color, fontWeight = FontWeight.SemiBold))) { open(url) },
        start,
        end,
    )
}

/** Простое сообщение без оформления: ссылки в нём синие и нажимаются, под текстом — «Перейти». */
@Composable
internal fun PlainWithLinks(text: String) {
    val ranges = remember(text) { urlsIn(text) }
    if (ranges.isEmpty()) {
        Caption(text, fontSize = TimaType.sz4)
        return
    }
    val open = rememberLinkOpen()
    val color = Tima.colors.link
    val annotated = buildAnnotatedString {
        append(text)
        for (r in ranges) linkAt(r.first, r.last + 1, openable(text.substring(r)), color, open)
    }
    Column {
        Caption(annotated, fontSize = TimaType.sz4)
        LinksUnder(ranges.map { openable(text.substring(it)) })
    }
}
