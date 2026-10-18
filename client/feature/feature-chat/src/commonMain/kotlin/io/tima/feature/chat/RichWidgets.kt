package io.tima.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
data class QuoteLook(val name: String?, val text: String?, val deleted: Boolean = false)

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

// ── Ряд: ячейки в строку, не вмещается — столбиком (ADR-0044 §6) ───────────

/** Наименьшая ширина ячейки по её содержимому: невидимого не бывает. */
private fun minWidthOf(cell: RowCell): Dp {
    val widgets = cell.widgets.maxOfOrNull { minWidthOf(it) } ?: 0.dp
    val text = if (cell.text != null) MIN_TEXT_CELL else 0.dp
    return maxOf(widgets, text, MIN_TEXT_CELL)
}

private fun minWidthOf(widget: RichBlock): Dp = when (widget) {
    is RichBlock.Image -> 96.dp
    is RichBlock.Link -> if (widget.mode == "card") 140.dp else 96.dp
    is RichBlock.Cloud, is RichBlock.Place, is RichBlock.Contact -> 120.dp
    is RichBlock.Quoted, is RichBlock.Reply -> 140.dp
    is RichBlock.Emoji -> 48.dp
    is RichBlock.Table -> 160.dp
    else -> MIN_TEXT_CELL
}

/** Ячейка с текстом — не уже этого: «99» шрифтом 22 и поля (Решения §10.3). */
private val MIN_TEXT_CELL = 72.dp

private const val CELL_GAP = 6

@Composable
internal fun RichRow(row: RichBlock.Row) {
    BoxWithConstraints(Modifier.fillMaxWidth().styled(row.style)) {
        val need = row.cells.sumOf { minWidthOf(it).value.toDouble() } + CELL_GAP * (row.cells.size - 1)
        if (need > maxWidth.value) {
            // Не вмещается — столбиком, каждая ячейка во всю ширину.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CELL_GAP.dp)) {
                for (cell in row.cells) RowCellView(cell, Modifier.fillMaxWidth())
            }
        } else {
            val total = row.cells.sumOf { it.width ?: 0 }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(CELL_GAP.dp)) {
                for (cell in row.cells) {
                    // Ширины в процентах; не названы (или сумма не 100) — поровну.
                    val weight = if (total == 100 && cell.width != null) cell.width!!.toFloat() else 1f
                    RowCellView(cell, Modifier.weight(weight).fillMaxHeight())
                }
            }
        }
    }
    DividerAfter(row.style)
}

@Composable
private fun RowCellView(cell: RowCell, modifier: Modifier) {
    val vertical = when (cell.style.valign) {
        "center" -> Alignment.CenterVertically
        "bottom" -> Alignment.Bottom
        else -> Alignment.Top
    }
    Column(modifier.styled(cell.style), verticalArrangement = Arrangement.spacedBy(4.dp, vertical)) {
        // Фон и поля — у ячейки; тексту остаются цвет, размер и выравнивание.
        val textStyle = cell.style.copy(background = null, padding = null, divider = null, dividerColor = null)
        cell.text?.takeIf { it.text.isNotEmpty() }?.let { StyledText(it, textStyle, TimaType.sz4) }
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
    val open = LocalUriHandler.current
    val go = { runCatching { open.openUri(link.url) } }
    when (link.mode) {
        // Кнопка (Решения §4): крупно домен — он из самой ссылки и не обрезается, ниже путь без схемы.
        "button" -> Column(
            Modifier.background(Tima.colors.navigation, RoundedCornerShape(10.dp)).clickable { go() }.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Caption(hostOf(link.url), fontSize = TimaType.sz4, weight = FontWeight.Bold, color = Tima.colors.onAccent, lineOne = true)
            val path = pathOf(link.url)
            if (link.title.isNotBlank()) Caption(link.title, fontSize = TimaType.sz5, color = Tima.colors.onAccent, lineOne = true)
            else if (path.isNotEmpty()) Caption(path, fontSize = TimaType.sz5, color = Tima.colors.onAccent, lineOne = true)
            if (link.url.startsWith("http://")) Caption(Tima.words.editor.unsafeLink, fontSize = TimaType.sz6, color = Tima.colors.onAccent)
        }
        "card" -> Column(
            Modifier.fillMaxWidth().background(Tima.colors.functional, RoundedCornerShape(8.dp)).clickable { go() }.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LocalRichMedia.current.getOrNull(link.media)?.let { m ->
                MessagePicture(mediaUri(m.mediaId, m.key), if (m.width > 0 && m.height > 0) m.width.toFloat() / m.height else null, Tima.words.chat.pictureMissing)
            }
            Caption(link.title.ifBlank { hostOf(link.url) }, fontSize = TimaType.sz4, weight = FontWeight.Bold)
            if (link.description.isNotBlank()) Caption(link.description, fontSize = TimaType.sz5, color = Tima.colors.text2)
            Tertiary(hostOf(link.url), lineOne = true)
        }
        else -> Caption(
            link.title.ifBlank { link.url },
            fontSize = TimaType.sz4,
            weight = FontWeight.SemiBold,
            color = Tima.colors.confirmed,
            modifier = Modifier.clickable { go() },
        )
    }
}

/** Домен ссылки — то, что человек видит до перехода (Решения §4). */
internal fun hostOf(url: String): String = io.tima.domain.chat.LinkSafety.displayHost(url)

/** Путь ссылки без схемы и домена — обрезается он, а не домен. */
internal fun pathOf(url: String): String = url.substringAfter("://").substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }

@Composable
private fun CloudWidget(cloud: RichBlock.Cloud) {
    val open = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().background(Tima.colors.functional, RoundedCornerShape(8.dp))
            .clickable { runCatching { open.openUri(cloud.url) } }.padding(8.dp),
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

@Composable
private fun QuotedWidget(quoted: RichBlock.Quoted) {
    val look = LocalQuoteLook.current(quoted.kind, quoted.chatId, quoted.messageId)
    val open = LocalOpenMessage.current
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(Tima.colors.functional, RoundedCornerShape(6.dp))
            .clickable { open(quoted.kind, quoted.chatId, quoted.messageId) },
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(Tima.colors.line))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            if (quoted.author && look.name != null) Caption(look.name, fontSize = TimaType.sz5, weight = FontWeight.Bold, lineOne = true)
            val text = when {
                look.deleted -> Tima.words.chat.deleted
                look.text != null -> look.text
                else -> Tima.words.chat.quotedElsewhere
            }
            // «cut» — три строки (по соседу в ряду — когда ряд научится мерить соседа), «full» — целиком.
            Caption(text, fontSize = TimaType.sz5, color = Tima.colors.text2, maxLines = if (quoted.cut == "full") Int.MAX_VALUE else 3)
        }
    }
}

@Composable
private fun UnderlayWidget(underlay: RichBlock.Underlay) {
    val look = LocalQuoteLook.current(underlay.kind, underlay.chatId, underlay.messageId)
    val open = LocalOpenMessage.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (underlay.author && look.name != null) Caption(look.name, fontSize = TimaType.sz5, weight = FontWeight.Bold, lineOne = true)
        when {
            look.deleted -> Tertiary(Tima.words.chat.deleted, lineOne = true)
            look.text != null -> Caption(look.text, fontSize = TimaType.sz4, maxLines = underlay.lines.coerceAtLeast(1))
            // Сервер не отдал — уровень не открыт читающему (Решения §11.3): «Недоступно» и что делать.
            else -> Tertiary(Tima.words.chat.underlayUnavailable)
        }
        Chip(Tima.words.chat.continueReading, kind = ChipKind.Quiet, onClick = { open(underlay.kind, underlay.chatId, underlay.messageId) })
    }
}
