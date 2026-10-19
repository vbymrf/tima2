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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
            for (block in content.blocks) RichBlockViewOf(block)
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
    )
}

@Composable
internal fun RichBlockViewOf(block: RichBlock) {
    val colors = Tima.colors
    when (block) {
        is RichBlock.Paragraph -> {
            if (block.style == io.tima.domain.chat.BlockStyle.NONE) Caption(annotated(block.text), fontSize = TimaType.sz4)
            else StyledText(block.text, block.style, TimaType.sz4)
            LinksUnder(linksOf(block.text))
            DividerAfter(block.style)
        }
        is RichBlock.Heading -> {
            StyledText(
                block.text,
                block.style,
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
        is RichBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.line))
            Column(Modifier.padding(start = 8.dp)) {
                for (line in block.lines) Caption(annotated(line), fontSize = TimaType.sz4, color = colors.text2)
                LinksUnder(block.lines.flatMap(::linksOf))
            }
        }
        is RichBlock.Bullets -> {
            BulletList(block.items, depth = 0)
            LinksUnder(allLinks(block.items))
        }
        is RichBlock.CodeBlock -> Box(
            Modifier.fillMaxWidth().background(colors.functional, RoundedCornerShape(6.dp)).padding(8.dp),
        ) {
            Caption(AnnotatedString(block.text, SpanStyle(fontFamily = FontFamily.Monospace)), fontSize = TimaType.sz5)
        }
        RichBlock.Divider -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(colors.line))
        is RichBlock.Table -> {
            Box(Modifier.fillMaxWidth().styled(block.style)) { RichTable(block) }
            DividerAfter(block.style)
        }
        is RichBlock.Fallback -> Caption(block.text, fontSize = TimaType.sz4)
        is RichBlock.Reply -> {
            val open = LocalOpenMessage.current
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

/** Пункты: «•», вложенные — «◦» и «▪» с отступом; глубже третьего уровня разметка не даёт. */
@Composable
private fun BulletList(items: List<Bullet>, depth: Int) {
    val mark = when (depth) { 0 -> "•"; 1 -> "◦"; else -> "▪" }
    Column(Modifier.padding(start = (depth * 14).dp)) {
        for (item in items) {
            Row {
                Caption(mark, fontSize = TimaType.sz4, modifier = Modifier.padding(end = 6.dp))
                Caption(annotated(item.text), fontSize = TimaType.sz4)
            }
            if (item.children.isNotEmpty()) BulletList(item.children, depth + 1)
        }
    }
}

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
