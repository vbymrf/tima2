package io.tima.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import io.tima.core.ui.Tima
import io.tima.core.ui.TimaType
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.LineKind
import io.tima.domain.chat.Typed
import io.tima.domain.chat.TypingMarks
import io.tima.domain.chat.TypingStyle

/** Что стоит перед строкой вида [kind] на экране: точка пункта, черта цитаты, линия разделителя. */
internal fun linePrefix(kind: LineKind): String = when (kind) {
    LineKind.LIST1 -> "• "
    LineKind.LIST2 -> "    ◦ "
    LineKind.LIST3 -> "        ▪ "
    LineKind.QUOTE -> "▎ "
    LineKind.DIVIDER -> "──────────"
    else -> ""
}

/**
 * Вид поля редактора и строки ввода (ADR-0048): текст без знаков, оформленный по отрезкам и видам
 * строк. Знаков в поле нет — курсор и стирание идут по видимым буквам. Точка пункта, черта цитаты и
 * линия разделителя стоят перед строкой и буквами не считаются: курсор в них не встаёт.
 *
 * [marks] — упоминания и метки цветом; [open] — «Знаки md» включены: открытый знак без пары серым,
 * он ждёт закрывающего.
 */
@Composable
internal fun rememberTypedView(
    typed: Typed,
    marks: TypingMarks?,
    open: Boolean,
    /** Цвет текста из стиля куска: цитата берёт его, как у получателя; нет — серым (заказчик 2026-10-24). */
    color: androidx.compose.ui.graphics.Color? = null,
): VisualTransformation {
    val colors = Tima.colors
    val marker = colors.text3
    val quote = color ?: colors.text2
    val highlight = colors.activity.copy(alpha = 0.35f)
    val code = colors.functional
    val tag = colors.confirmed
    return remember(typed, marks, open, marker, quote, highlight, code, tag) {
        VisualTransformation { text ->
            // Поле успело смениться раньше вида — показать как есть, без оформления.
            if (text.text != typed.text) return@VisualTransformation TransformedText(text, OffsetMapping.Identity)
            val lines = typed.text.split('\n')
            val kinds = lines.indices.map { typed.lines.getOrElse(it) { LineKind.PARAGRAPH } }
            val prefixes = kinds.map(::linePrefix)
            val from = IntArray(lines.size)
            val shownFrom = IntArray(lines.size)
            val b = AnnotatedString.Builder()
            var o = 0
            for (i in lines.indices) {
                if (i > 0) b.append('\n')
                from[i] = o
                shownFrom[i] = b.length
                if (prefixes[i].isNotEmpty()) {
                    val at = b.length
                    b.append(prefixes[i])
                    b.addStyle(SpanStyle(color = marker), at, b.length)
                }
                val at = b.length
                b.append(lines[i])
                val style = when (kinds[i]) {
                    // Размеры — те же, что в ленте (заказчик 2026-10-20: 22, 20, 18).
                    LineKind.HEADING1 -> SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = TimaType.heading1)
                    LineKind.HEADING2 -> SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = TimaType.heading2)
                    LineKind.HEADING3 -> SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = TimaType.heading3)
                    LineKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic, color = quote)
                    LineKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = code)
                    else -> null
                }
                if (style != null && b.length > at) b.addStyle(style, at, b.length)
                o += lines[i].length + 1
            }
            fun lineOf(off: Int): Int {
                var i = 0
                while (i + 1 < lines.size && from[i + 1] <= off) i++
                return i
            }
            fun toShown(off: Int): Int {
                val i = lineOf(off)
                return shownFrom[i] + prefixes[i].length + (off - from[i]).coerceIn(0, lines[i].length)
            }
            for (s in typed.spans) {
                val style = when (s.kind) {
                    InlineKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    InlineKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    InlineKind.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                    InlineKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    InlineKind.HIGHLIGHT -> SpanStyle(background = highlight)
                    InlineKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = code)
                }
                val a = toShown(s.start)
                val e = toShown(s.end)
                if (e > a) b.addStyle(style, a, e)
            }
            if (marks != null) for (m in marks.of(typed.text)) {
                val style = when (m.style) {
                    TypingStyle.OPEN -> if (open) SpanStyle(color = marker) else null
                    TypingStyle.MENTION, TypingStyle.HASHTAG -> SpanStyle(color = tag)
                    else -> null
                } ?: continue
                val a = toShown(m.start)
                val e = toShown(m.end)
                if (e > a) b.addStyle(style, a, e)
            }
            TransformedText(
                b.toAnnotatedString(),
                object : OffsetMapping {
                    override fun originalToTransformed(offset: Int): Int = toShown(offset.coerceIn(0, typed.text.length))
                    override fun transformedToOriginal(offset: Int): Int {
                        var i = 0
                        while (i + 1 < lines.size && shownFrom[i + 1] <= offset) i++
                        return from[i] + (offset - shownFrom[i] - prefixes[i].length).coerceIn(0, lines[i].length)
                    }
                },
            )
        }
    }
}
