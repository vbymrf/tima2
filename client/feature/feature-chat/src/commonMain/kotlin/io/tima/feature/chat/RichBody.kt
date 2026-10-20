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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.tima.core.ui.Caption
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.core.ui.words
import io.tima.domain.chat.Bullet
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichText
import io.tima.domain.chat.SpanKind

/**
 * Оформленное сообщение в пузыре (ПЛАН-(РС) РС4, doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §7).
 *
 * Цвета и размеры — только из темы: быстрое оформление цветов не задаёт (Решения §7), а защитный
 * пол темы действует сам. Горизонтальной прокрутки нет нигде (Решения §10.3): таблица делит
 * ширину пузыря поровну, текст в ячейке переносится.
 */
@Composable
fun RichBody(content: RichContent, modifier: Modifier = Modifier) {
    androidx.compose.runtime.CompositionLocalProvider(LocalRichMedia provides content.media) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((i, block) in content.blocks.withIndex()) {
                if (block is RichBlock.Underlay && LocalRefDepth.current == 0) {
                    // Подкладка — как ещё одно сообщение внутри пузыря (заказчик 2026-10-20): во всю его
                    // ширину; соседние подкладки разделены тонкой линией цвета рамки.
                    if (content.blocks.getOrNull(i - 1) is RichBlock.Underlay) UnderlaySeparator()
                    androidx.compose.runtime.CompositionLocalProvider(LocalUnderlayBleed provides true) { RichBlockViewOf(block) }
                } else {
                    RichBlockViewOf(block)
                }
            }
        }
    }
}

/** Текст блока со стилем: цвет и размер — по именам и ступеням, фон и поля — у блока. */
@Composable
internal fun StyledText(text: RichText, style: io.tima.domain.chat.BlockStyle, base: androidx.compose.ui.unit.TextUnit, weight: FontWeight = FontWeight.Normal) {
    Caption(
        annotated(text),
        modifier = Modifier.fillMaxWidth().styled(style),
        fontSize = sizeOf(style.size, base),
        weight = weight,
        color = io.tima.core.ui.paletteColor(style.color) ?: Tima.colors.text,
        textAlign = alignOf(style.align),
        maxLines = LocalRefLines.current,
    )
}

/** Стиль ячейки, в которой стоит блок (ADR-0048 §4): тексту внутри — её цвет, размер и выравнивание. */
internal val LocalCellText = androidx.compose.runtime.staticCompositionLocalOf { io.tima.domain.chat.BlockStyle.NONE }

/** Свой стиль блока поверх стиля ячейки: чего у блока нет — берётся у ячейки. */
internal fun io.tima.domain.chat.BlockStyle.over(base: io.tima.domain.chat.BlockStyle): io.tima.domain.chat.BlockStyle =
    if (base == io.tima.domain.chat.BlockStyle.NONE) this else copy(color = color ?: base.color, size = size ?: base.size, align = align ?: base.align)

@Composable
internal fun RichBlockViewOf(block: RichBlock) {
    val colors = Tima.colors
    val cell = LocalCellText.current
    when (block) {
        is RichBlock.Paragraph -> {
            // Адрес в тексте заменяется пузырём «Перейти · домен» под абзацем (заказчик 2026-10-19).
            val shown = withoutLinks(block.text)
            val style = block.style.over(cell)
            if (shown.text.isNotEmpty()) {
                if (style == io.tima.domain.chat.BlockStyle.NONE) Caption(annotated(shown), fontSize = TimaType.sz4, maxLines = LocalRefLines.current)
                else StyledText(shown, style, TimaType.sz4)
            }
            LinksUnder(linksOf(block.text))
            DividerAfter(block.style)
        }
        is RichBlock.Heading -> {
            StyledText(
                withoutLinks(block.text),
                block.style.over(cell),
                when (block.level) { 1 -> TimaType.sz2; 2 -> TimaType.sz3; else -> TimaType.sz4 },
                FontWeight.ExtraBold,
            )
            LinksUnder(linksOf(block.text))
            DividerAfter(block.style)
        }
        is RichBlock.Row -> RichRow(block)
        // Вложения внутри смешанного тела — тем же видом, что и вне пузыря.
        is RichBlock.Album, is RichBlock.File -> AttachmentsView(io.tima.domain.chat.RichContent(listOf(block), LocalRichMedia.current))
        is RichBlock.Image, is RichBlock.Link, is RichBlock.Cloud, is RichBlock.Place, is RichBlock.Contact,
        is RichBlock.Emoji, is RichBlock.Quoted, is RichBlock.Underlay -> WidgetView(block)
        // Цитата, список и код — со стилем куска, как абзац (doc_mig/РЕДАКТОР-СВОЙСТВА-И-ВЛОЖЕНИЕ.md).
        is RichBlock.Quote -> {
            val st = block.style.over(cell)
            Row(Modifier.fillMaxWidth().styled(block.style).height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(colors.line))
                Column(Modifier.padding(start = 8.dp)) {
                    for (line in block.lines) {
                        Caption(
                            annotated(withoutLinks(line)),
                            modifier = Modifier.fillMaxWidth(),
                            fontSize = sizeOf(st.size, TimaType.sz4),
                            color = io.tima.core.ui.paletteColor(st.color) ?: colors.text2,
                            textAlign = alignOf(st.align),
                        )
                    }
                    LinksUnder(block.lines.flatMap(::linksOf))
                }
            }
            DividerAfter(block.style)
        }
        is RichBlock.Bullets -> {
            Column(Modifier.fillMaxWidth().styled(block.style)) {
                androidx.compose.runtime.CompositionLocalProvider(LocalCellText provides block.style.over(cell).copy(background = null, padding = null, divider = null, dividerColor = null)) {
                    BulletList(block.items, depth = 0, closed = block.closed, flat = block.flat)
                }
                LinksUnder(allLinks(block.items))
            }
            DividerAfter(block.style)
        }
        is RichBlock.CodeBlock -> {
            val st = block.style.over(cell)
            Box(
                Modifier.fillMaxWidth().background(colors.functional, RoundedCornerShape(6.dp)).styled(block.style).padding(8.dp),
            ) {
                Caption(
                    AnnotatedString(block.text, SpanStyle(fontFamily = FontFamily.Monospace)),
                    modifier = Modifier.fillMaxWidth(),
                    fontSize = sizeOf(st.size, TimaType.sz5),
                    color = io.tima.core.ui.paletteColor(st.color) ?: colors.text,
                    textAlign = alignOf(st.align),
                )
            }
            DividerAfter(block.style)
        }
        RichBlock.Divider -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(colors.line))
        is RichBlock.Table -> {
            Box(Modifier.fillMaxWidth().styled(block.style)) { RichTable(block) }
            DividerAfter(block.style)
        }
        is RichBlock.Fallback -> Caption(block.text, fontSize = TimaType.sz4)
        is RichBlock.Reply -> {
            // Переход — только через окно перехода (заказчик 2026-10-20).
            val open = rememberMessageOpen()
            ReplyBlock(LocalReplyLook.current(block)) { open("reply", block.chatId, block.messageId) }
        }
    }
}

/**
 * Как показать блок ответа: имя автора и строка из его сообщения — по своей переписке (в теле
 * только ссылка). Нет сообщения на устройстве — только имя; удалено — «Сообщение удалено»
 * (Решения §7).
 */
data class ReplyLook(val name: String?, val preview: String?, val deleted: Boolean = false)

/** Переписка называет, на что ответ; по умолчанию — ничего не знаем, видно только знак ответа. */
val LocalReplyLook = staticCompositionLocalOf<(RichBlock.Reply) -> ReplyLook> { { ReplyLook(null, null) } }

/** Блок ответа: полоса, имя, строка из сообщения; нажатие — переход к нему (§11.4). */
@Composable
fun ReplyBlock(look: ReplyLook, onClick: (() -> Unit)? = null) {
    val colors = Tima.colors
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(colors.functional, RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.activity))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Caption(look.name ?: "↩", fontSize = TimaType.sz5, weight = FontWeight.Bold, maxLines = 1)
            val second = if (look.deleted) Tima.words.chat.deleted else look.preview
            if (!second.isNullOrBlank()) Caption(second, fontSize = TimaType.sz5, color = colors.text2, maxLines = 1)
        }
    }
}

/**
 * Пункты: «•», вложенные — «◦» и «▪»; глубже третьего уровня разметка не даёт. Пункт — контейнер
 * (ADR-0049): под заголовком его блоки, виджеты и вложенные пункты; есть что под ним — стрелка
 * «▾»/«▸» сворачивает и разворачивает, заголовок виден всегда. [closed] — список пришёл свёрнутым;
 * [flat] — без отступа: вложенное с начала строки, без маркера ребёнка, у точки — узкий зазор.
 */
@Composable
private fun BulletList(items: List<Bullet>, depth: Int, closed: Boolean, flat: Boolean) {
    val mark = when (depth) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
    // В ячейке — её цвет и размер (ADR-0048 §4).
    val cell = LocalCellText.current
    val size = sizeOf(cell.size, TimaType.sz4)
    val color = io.tima.core.ui.paletteColor(cell.color) ?: Tima.colors.text
    // Место под стрелку — у всех пунктов уровня, если она есть хоть у одного: точки стоят ровно.
    val arrows = items.any { it.blocks.isNotEmpty() || it.children.isNotEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (item in items) {
            // Свой стиль пункта — как у ячейки (заказчик 2026-10-20): фон и поля — у пункта, цвет,
            // размер и выравнивание — заголовку и всему под ним.
            val own = item.style.over(cell)
            val itemSize = sizeOf(own.size, TimaType.sz4)
            val itemColor = io.tima.core.ui.paletteColor(own.color) ?: Tima.colors.text
            Column(Modifier.fillMaxWidth().styled(item.style)) {
            androidx.compose.runtime.CompositionLocalProvider(LocalCellText provides own.copy(background = null, padding = null, divider = null, dividerColor = null)) {
            // «Списком» (заказчик 2026-10-20): пункт без заголовка со вставленным сообщением — заголовок
            // «имя владельца» или «Сообщение», под ним само сообщение.
            val refTitle = item.text.text.isEmpty() && (item.blocks.firstOrNull() is RichBlock.Underlay || item.blocks.firstOrNull() is RichBlock.Quoted)
            // Пункт без заголовка (ADR-0050 §1) — его блоки у самой точки.
            val bare = item.text.text.isEmpty() && item.blocks.isNotEmpty() && !refTitle
            val inside = (!bare && item.blocks.isNotEmpty()) || item.children.isNotEmpty()
            var open by androidx.compose.runtime.saveable.rememberSaveable(item.text.text, closed) { mutableStateOf(!closed) }
            Row {
                if (arrows) {
                    Box(Modifier.width(14.dp).then(if (inside) Modifier.clickable { open = !open }.testTag(BULLET_FOLD_TAG) else Modifier)) {
                        if (inside) Caption(if (open) "▾" else "▸", fontSize = size, color = Tima.colors.text2)
                    }
                }
                if (!(flat && depth > 0)) Caption(mark, fontSize = itemSize, color = itemColor, modifier = Modifier.padding(end = if (flat) 2.dp else 6.dp))
                if (bare) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { for (b in item.blocks) RichBlockViewOf(b) }
                } else if (refTitle) {
                    Box(Modifier.weight(1f)) { RefTitle(item.blocks.first(), itemSize, itemColor) }
                } else {
                    Caption(annotated(withoutLinks(item.text)), modifier = Modifier.weight(1f), fontSize = itemSize, color = itemColor, textAlign = alignOf(own.align))
                }
            }
            if (inside && open) {
                Column(Modifier.padding(start = if (flat) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (refTitle) {
                        androidx.compose.runtime.CompositionLocalProvider(LocalRefTitled provides true) { for (b in item.blocks) RichBlockViewOf(b) }
                    } else if (!bare) {
                        for (b in item.blocks) RichBlockViewOf(b)
                    }
                    if (item.children.isNotEmpty()) BulletList(item.children, depth + 1, closed, flat)
                }
            }
            }
            }
            DividerAfter(item.style)
        }
    }
}

/** Стрелка пункта списка — свернуть и развернуть (сценарии Maestro). */
const val BULLET_FOLD_TAG = "bullet:fold"

/**
 * Таблица — ширина пузыря поровну на столбцы, без прокрутки. Слитая ячейка (`^^`, `<<`) рисуется
 * пустой, без своей рамки сверху или слева — по ней видно, что она продолжает соседнюю.
 */
@Composable
private fun RichTable(table: RichBlock.Table) {
    val colors = Tima.colors
    val columns = table.rows.maxOfOrNull { it.size } ?: return
    Column(Modifier.fillMaxWidth().background(colors.line).padding(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        table.rows.forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                for (col in 0 until columns) {
                    val cell = row.getOrNull(col)
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(if (rowIndex == 0) colors.functional else colors.surface)
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    ) {
                        val text = cell?.text
                        if (text != null && cell.merge == null) {
                            Caption(
                                annotated(text),
                                fontSize = TimaType.sz5,
                                weight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Адреса ссылок в тексте — для «Перейти» под абзацем. */
internal fun linksOf(text: RichText): List<String> = text.spans.filter { it.kind == SpanKind.LINK }.mapNotNull { span ->
    val start = span.start.coerceIn(0, text.text.length)
    val end = span.end.coerceIn(start, text.text.length)
    span.attribute.ifBlank { text.text.substring(start, end) }.takeIf { it.isNotBlank() }?.let(::openable)
}

private fun allLinks(items: List<Bullet>): List<String> = items.flatMap { linksOf(it.text) + allLinks(it.children) }

/**
 * Отрезки оформления → `AnnotatedString`. Цвета — из темы; маркер — янтарь темы. Ссылка — цветом
 * ссылок из «Цветов» и нажимается: открывается через окно с доменом (заказчик 2026-10-19).
 */
@Composable
internal fun annotated(text: RichText): AnnotatedString {
    val colors = Tima.colors
    val highlight = colors.activity.copy(alpha = 0.35f)
    val code = colors.functional
    val open = rememberLinkOpen()
    return buildAnnotatedString {
        append(text.text)
        for (span in text.spans) {
            val start = span.start.coerceIn(0, text.text.length)
            val end = span.end.coerceIn(start, text.text.length)
            if (end <= start) continue
            if (span.kind == SpanKind.LINK) {
                linkAt(start, end, openable(span.attribute.ifBlank { text.text.substring(start, end) }), colors.link, open)
                continue
            }
            val style = when (span.kind) {
                SpanKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                SpanKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                SpanKind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                SpanKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                SpanKind.HIGHLIGHT -> SpanStyle(background = highlight)
                SpanKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = code)
                SpanKind.LINK, SpanKind.MENTION, SpanKind.HASHTAG -> SpanStyle(color = colors.confirmed, fontWeight = FontWeight.SemiBold)
            }
            addStyle(style, start, end)
        }
    }
}
