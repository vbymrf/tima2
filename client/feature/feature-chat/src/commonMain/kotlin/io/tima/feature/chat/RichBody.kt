package io.tima.feature.chat

import androidx.compose.foundation.background
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in content.blocks) RichBlockView(block)
    }
}

@Composable
private fun RichBlockView(block: RichBlock) {
    val colors = Tima.colors
    when (block) {
        is RichBlock.Paragraph -> Caption(annotated(block.text), fontSize = TimaType.sz4)
        is RichBlock.Heading -> Caption(
            annotated(block.text),
            fontSize = when (block.level) { 1 -> TimaType.sz2; 2 -> TimaType.sz3; else -> TimaType.sz4 },
            weight = FontWeight.ExtraBold,
        )
        is RichBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.line))
            Column(Modifier.padding(start = 8.dp)) {
                for (line in block.lines) Caption(annotated(line), fontSize = TimaType.sz4, color = colors.text2)
            }
        }
        is RichBlock.Bullets -> BulletList(block.items, depth = 0)
        is RichBlock.CodeBlock -> Box(
            Modifier.fillMaxWidth().background(colors.functional, RoundedCornerShape(6.dp)).padding(8.dp),
        ) {
            Caption(AnnotatedString(block.text, SpanStyle(fontFamily = FontFamily.Monospace)), fontSize = TimaType.sz5)
        }
        RichBlock.Divider -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(colors.line))
        is RichBlock.Table -> RichTable(block)
        is RichBlock.Fallback -> Caption(block.text, fontSize = TimaType.sz4)
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

/** Отрезки оформления → `AnnotatedString`. Цвета — из темы; маркер — янтарь темы. */
@Composable
private fun annotated(text: RichText): AnnotatedString {
    val colors = Tima.colors
    val highlight = colors.activity.copy(alpha = 0.35f)
    val code = colors.functional
    return buildAnnotatedString {
        append(text.text)
        for (span in text.spans) {
            val style = when (span.kind) {
                SpanKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                SpanKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                SpanKind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                SpanKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                SpanKind.HIGHLIGHT -> SpanStyle(background = highlight)
                SpanKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = code)
                SpanKind.LINK, SpanKind.MENTION, SpanKind.HASHTAG -> SpanStyle(color = colors.confirmed, fontWeight = FontWeight.SemiBold)
            }
            val start = span.start.coerceIn(0, text.text.length)
            val end = span.end.coerceIn(start, text.text.length)
            if (end > start) addStyle(style, start, end)
        }
    }
}
