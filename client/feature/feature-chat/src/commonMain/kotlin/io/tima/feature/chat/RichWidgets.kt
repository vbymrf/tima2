package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.tima.core.media.mediaUri
import io.tima.core.ui.Caption
import io.tima.core.ui.Chip
import io.tima.core.ui.ChipKind
import io.tima.core.ui.MessagePicture
import io.tima.core.ui.Tertiary
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.paletteColor
import io.tima.core.ui.words
import io.tima.domain.chat.BlockStyle
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichMedia
import io.tima.domain.chat.RowCell

/** Файлы сообщения — для виджетов картинки и карточки ссылки (номер → файл). */
internal val LocalRichMedia = staticCompositionLocalOf<List<RichMedia>> { emptyList() }

/**
 * Как показать ссылку на другое сообщение (вставленное, подкладка): переписка называет автора и
 * текст по себе. Нет сообщения на устройстве — только знак ссылки и «Продолжить чтение»;
 * получение по номеру — ПЛАН-(РС) РС7.
 */
data class QuoteLook(
    val name: String?,
    val text: String?,
    val deleted: Boolean = false,
    /** Сервер не отдал: причина (`level`, `closed`) и уровень — «что сделать» по положению читателя. */
    val why: String? = null,
    val whyLevel: Int? = null,
)

val LocalQuoteLook = staticCompositionLocalOf<(kind: String, chatId: String, messageId: Long) -> QuoteLook> {
    { _, _, _ -> QuoteLook(null, null) }
}

/** «Продолжить чтение» и переход к вставленному — что делает переписка; по умолчанию ничего. */
val LocalOpenMessage = staticCompositionLocalOf<(kind: String, chatId: String, messageId: Long) -> Unit> { { _, _, _ -> } }

// ── Стиль блока: имена и ступени (ADR-0044 §4) ──────────────────────────────

internal fun paddingOf(step: String?): Dp = when (step) {
    "none" -> 0.dp
    "small" -> 4.dp
    "large" -> 16.dp
    "normal" -> 8.dp
    else -> 0.dp
}

internal fun sizeOf(step: String?, base: TextUnit): TextUnit = when (step) {
    "smaller" -> TimaType.sz5
    "larger" -> if (base == TimaType.sz4) TimaType.sz3 else TimaType.sz2
    else -> base
}

internal fun alignOf(step: String?): TextAlign = when (step) {
    "center" -> TextAlign.Center
    "end" -> TextAlign.End
    else -> TextAlign.Start
}

/** Фон и поля блока по стилю; разделитель после — отдельно ([DividerAfter]). */
@Composable
internal fun Modifier.styled(style: BlockStyle): Modifier {
    val bg = paletteColor(style.background)
    val pad = paddingOf(style.padding).let { if (it == 0.dp && bg != null) 6.dp else it }
    return (if (bg != null) this.background(bg, RoundedCornerShape(6.dp)) else this).padding(pad)
}

/** Разделитель после блока: `1`, `2`, `4` точки; `hidden` и пусто — нет. */
@Composable
internal fun DividerAfter(style: BlockStyle) {
    val thickness = style.divider?.toIntOrNull() ?: return
    val color = paletteColor(style.dividerColor) ?: Tima.colors.line
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(thickness.dp).background(color))
}

// ── Ряд: строки × столбцы, всегда в одну строку (ADR-0044 §6; заказчик 2026-10-18) ──

private const val CELL_GAP = 6

/**
 * Ряд — сеткой [SpanGrid]: столбцы по процентам, слитые ячейки прямоугольником. Столбиком не встаёт
 * (заказчик 2026-10-18): не помещающийся ряд не отправляется, а у узкого получателя текст
 * переносится, виджеты сжимаются.
 */
@Composable
internal fun RichRow(row: RichBlock.Row) {
    val columns = row.columns.coerceAtLeast(1)
    val first = row.cells.take(columns)
    // Ширины в процентах; не названы (или сумма не 100) — поровну.
    val named = first.all { it.width != null } && first.sumOf { it.width ?: 0 } == 100
    val widths = first.map { if (named) it.width!! else 1 }
    val spans = remember(row) { io.tima.domain.chat.GridSpans.of(row.cells.map { it.merge }, columns) }
    SpanGrid(columns, widths, spans, CELL_GAP.dp, Modifier.fillMaxWidth().styled(row.style)) { i ->
        RowCellView(row.cells[i], Modifier, row.style)
    }
    DividerAfter(row.style)
}

@Composable
private fun RowCellView(cell: RowCell, modifier: Modifier, rowStyle: io.tima.domain.chat.BlockStyle = io.tima.domain.chat.BlockStyle.NONE) {
    val vertical = when (cell.style.valign) {
        "center" -> Alignment.CenterVertically
        "bottom" -> Alignment.Bottom
        else -> Alignment.Top
    }
    Column(modifier.styled(cell.style), verticalArrangement = Arrangement.spacedBy(4.dp, vertical)) {
        // Фон и поля — у ячейки; тексту остаются цвет, размер и выравнивание.
        // Цвет, размер и выравнивание ряда — ячейкам, если у ячейки своих нет.
        val textStyle = cell.style.copy(background = null, padding = null, divider = null, dividerColor = null)
            .over(rowStyle.copy(background = null, padding = null, divider = null, dividerColor = null))
        cell.text?.takeIf { it.text.isNotEmpty() }?.let {
            val shown = withoutLinks(it)
            if (shown.text.isNotEmpty()) StyledText(shown, textStyle, TimaType.sz4)
            LinksUnder(linksOf(it))
        }
        // Блоки ячейки — со стилем ячейки поверх (ADR-0048 §4): цвет, размер, выравнивание.
        if (cell.blocks.isNotEmpty()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalCellText provides textStyle) {
                for (b in cell.blocks) RichBlockViewOf(b)
            }
        }
        for (w in cell.widgets) WidgetView(w)
    }
}

// ── Виджеты (ADR-0044 §3) ───────────────────────────────────────────────────

@Composable
internal fun WidgetView(block: RichBlock) {
    when (block) {
        is RichBlock.Image -> ImageWidget(block)
        is RichBlock.Link -> LinkWidget(block)
        is RichBlock.Cloud -> CloudWidget(block)
        is RichBlock.Place -> PlaceWidget(block)
        is RichBlock.Contact -> ContactWidget(block)
        is RichBlock.Emoji -> Caption(block.text, fontSize = 40.sp)
        is RichBlock.Quoted -> QuotedWidget(block)
        is RichBlock.Underlay -> UnderlayWidget(block)
        is RichBlock.Reply -> {
            val open = LocalOpenMessage.current
            ReplyBlock(LocalReplyLook.current(block)) { open("reply", block.chatId, block.messageId) }
        }
        else -> RichBlockViewOf(block)
    }
}

@Composable
private fun ImageWidget(image: RichBlock.Image) {
    val media = LocalRichMedia.current.getOrNull(image.media)
    if (media == null) {
        Tertiary(Tima.words.chat.pictureMissing, lineOne = true)
        return
    }
    val aspect = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else null
    MessagePicture(mediaUri(media.mediaId, media.key), aspect, Tima.words.chat.pictureMissing)
}

@Composable
private fun LinkWidget(link: RichBlock.Link) {
    val open = rememberLinkOpen()
    val go = { open(link.url) }
    // Цвет ссылки — заданный в сообщении, иначе цвет ссылок из «Цветов» (заказчик 2026-10-19).
    val color = paletteColor(link.color) ?: Tima.colors.link
    when {
        // Карточка — заголовок, описание, картинка; домен цветом ссылки.
        link.mode == "card" -> Column(
            Modifier.fillMaxWidth().background(Tima.colors.functional, RoundedCornerShape(8.dp)).clickable { go() }.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LocalRichMedia.current.getOrNull(link.media)?.let { m ->
                MessagePicture(mediaUri(m.mediaId, m.key), if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else null, Tima.words.chat.pictureMissing)
            }
            Caption(link.title.ifBlank { hostOf(link.url) }, fontSize = TimaType.sz4, weight = FontWeight.Bold)
            if (link.description.isNotBlank()) Caption(link.description, fontSize = TimaType.sz5, color = Tima.colors.text2)
            Caption(hostOf(link.url), fontSize = TimaType.sz5, weight = FontWeight.SemiBold, color = color, lineOne = true)
        }
        // Текстом с названием — название цветом ссылки.
        link.mode == "plain" && link.title.isNotBlank() -> Caption(
            link.title,
            fontSize = TimaType.sz4,
            weight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.clickable { go() },
        )
        // Кнопкой, и текстом без названия — пузырь «Перейти · домен» (заказчик 2026-10-19): есть
        // название — оно вместо «Перейти»; полный адрес — только в окне перехода.
        else -> Caption(
            linkLabel(link.title.ifBlank { Tima.words.chat.linkGo }, link.url),
            fontSize = TimaType.sz5,
            weight = FontWeight.Bold,
            color = Tima.colors.onAccent,
            lineOne = true,
            modifier = Modifier.background(color, RoundedCornerShape(12.dp)).clickable { go() }
                .padding(horizontal = 12.dp, vertical = 6.dp).testTag(LinkTags.GO),
        )
    }
}

/** Домен ссылки — то, что человек видит до перехода (Решения §4). */
internal fun hostOf(url: String): String = io.tima.domain.chat.LinkSafety.displayHost(url)

/** Путь ссылки без схемы и домена — обрезается он, а не домен. */
internal fun pathOf(url: String): String = url.substringAfter("://").substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }

@Composable
private fun CloudWidget(cloud: RichBlock.Cloud) {
    val open = rememberLinkOpen()
    Row(
        Modifier.fillMaxWidth().background(Tima.colors.functional, RoundedCornerShape(8.dp))
            .clickable { open(cloud.url) }.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(Tima.words.chat.cloudKind(cloud.kind), fontSize = TimaType.sz5, weight = FontWeight.Bold, color = Tima.colors.text2)
        Column(Modifier.weight(1f)) {
            Caption(cloud.title.ifBlank { hostOf(cloud.url) }, fontSize = TimaType.sz4, lineOne = true)
            Tertiary(hostOf(cloud.url), lineOne = true)
        }
    }
}

@Composable
private fun PlaceWidget(place: RichBlock.Place) {
    val open = LocalUriHandler.current
    val w = Tima.words.chat
    Column(
        Modifier.fillMaxWidth().background(Tima.colors.functional, RoundedCornerShape(8.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Caption(place.label.ifBlank { w.place }, fontSize = TimaType.sz4, weight = FontWeight.Bold)
        Tertiary("${round5(place.lat)}, ${round5(place.lon)}", lineOne = true)
        // Выбор карты (РЕДАКТОР-СООБЩЕНИЙ §10.4): картинку не рисуем, человек открывает свою.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(w.placeYandex, kind = ChipKind.Quiet, onClick = { runCatching { open.openUri(yandexMapUrl(place.lat, place.lon)) } })
            Chip(w.placeGoogle, kind = ChipKind.Quiet, onClick = { runCatching { open.openUri(googleMapUrl(place.lat, place.lon)) } })
        }
    }
}

/** Точка на Яндекс Картах: приложение перехватит ссылку, если стоит; нет — откроется сайт. */
internal fun yandexMapUrl(lat: Double, lon: Double): String = "https://yandex.ru/maps/?pt=$lon,$lat&z=16&l=map"

internal fun googleMapUrl(lat: Double, lon: Double): String = "https://www.google.com/maps/search/?api=1&query=$lat,$lon"

private fun round5(v: Double): String {
    val scaled = kotlin.math.round(v * 100_000) / 100_000
    return scaled.toString()
}

@Composable
private fun ContactWidget(contact: RichBlock.Contact) {
    Column(Modifier.fillMaxWidth().background(Tima.colors.functional, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Caption(contact.name.ifBlank { Tima.words.chat.contact }, fontSize = TimaType.sz4, weight = FontWeight.Bold)
        if (contact.phone.isNotBlank()) Tertiary(contact.phone, lineOne = true)
    }
}

/** Подборка (ADR-0050 §1): сокращённо — карточкой, целиком — простым текстом; свойства — общие с подкладкой. */
@Composable
private fun QuotedWidget(quoted: RichBlock.Quoted) = MessageRef(
    quoted.kind, quoted.chatId, quoted.messageId, quoted.author, quoted.lines, quoted.more, quoted.moreText, quoted.tap,
)

/** Подкладка (ADR-0050 §1): под пузырём, по умолчанию целиком и текстом; разделитель — стилем блока. */
@Composable
private fun UnderlayWidget(underlay: RichBlock.Underlay) {
    Box(Modifier.fillMaxWidth().styled(underlay.style)) {
        MessageRef(underlay.kind, underlay.chatId, underlay.messageId, underlay.author, underlay.lines, underlay.more, underlay.moreText, underlay.tap)
    }
    DividerAfter(underlay.style)
}

/**
 * Ссылка на сообщение — подборка и подкладка (ADR-0050 §1). [lines] 0 — целиком, простым текстом;
 * иначе — первые строки карточкой. [tap] — нажимается всё: зелёная полоса слева; [more] — зелёная
 * кнопка «Продолжить» (`default`, `blank` — без текста, `custom` — [moreText]). Нет ни того, ни
 * другого — не нажимается.
 */
@Composable
private fun MessageRef(kind: String, chatId: String, messageId: Long, author: Boolean, lines: Int, more: String, moreText: String, tap: Boolean) {
    val colors = Tima.colors
    val look = LocalQuoteLook.current(kind, chatId, messageId)
    // Переход — только через окно перехода (заказчик 2026-10-20).
    val open = rememberMessageOpen()
    val full = lines == 0
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .then(if (full) Modifier else Modifier.background(colors.functional, RoundedCornerShape(6.dp)))
            .then(if (tap) Modifier.clickable { open(kind, chatId, messageId) } else Modifier)
            .testTag(if (tap) "message.ref.tap" else "message.ref"),
    ) {
        if (tap) Box(Modifier.width(4.dp).fillMaxHeight().background(colors.navigation, RoundedCornerShape(2.dp)))
        Column(
            Modifier.padding(start = if (tap || !full) 8.dp else 0.dp, end = if (full) 0.dp else 8.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (author && look.name != null) Caption(look.name, fontSize = TimaType.sz5, weight = FontWeight.Bold, lineOne = true)
            when {
                look.deleted -> Tertiary(Tima.words.chat.deleted, lineOne = true)
                look.text != null -> Caption(
                    look.text,
                    fontSize = if (full) TimaType.sz4 else TimaType.sz5,
                    color = if (full) colors.text else colors.text2,
                    maxLines = if (full) Int.MAX_VALUE else lines,
                )
                // Сервер не отдал — уровень не открыт читающему (Решения §11.3): «Недоступно» и что делать —
                // по причине, которую назвал сервер; не спрашивали ещё — общей строкой.
                look.why != null -> Tertiary(Tima.words.showcase.peekUnavailable(look.why, look.whyLevel))
                else -> Tertiary(Tima.words.chat.quotedElsewhere)
            }
            if (more.isNotEmpty()) {
                val label = when (more) {
                    "blank" -> "›"
                    "custom" -> moreText.ifBlank { "›" }
                    else -> Tima.words.chat.continueReading
                }
                Chip(label, kind = ChipKind.Selected, onClick = { open(kind, chatId, messageId) }, modifier = Modifier.testTag("message.ref.more"))
            }
        }
    }
}
