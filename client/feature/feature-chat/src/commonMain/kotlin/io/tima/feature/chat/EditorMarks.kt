package io.tima.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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
import io.tima.domain.chat.TextEdit
import io.tima.domain.chat.TypingMarks
import io.tima.domain.chat.TypingSpan
import io.tima.domain.chat.TypingStyle

/**
 * Знаки быстрого оформления в редакторе спрятаны, как в Word (заказчик 2026-10-18: «Символы разметки
 * скрывать при редакторе»): видно оформленное, а не `**`. Документ при этом остаётся набранным со
 * знаками — прячется только вид.
 *
 * Знак строки целиком (```` ``` ````, `---`) остаётся виден: спрятанный, он оставил бы пустую строку.
 * Знак пункта `- ` показан точкой. Курсор в спрятанное не встаёт: место на экране — наименьшее место
 * в набранном, то есть сразу за видимой буквой (набор у конца жирного продолжает жирный), а в начале
 * строки — за её знаками (набор в начале заголовка остаётся заголовком).
 */
internal class HiddenMarks private constructor(
    val shown: String,
    private val hiddenAt: BooleanArray,
    private val shownAt: IntArray,
    private val sourceAt: IntArray,
) {
    /** Знак [i] набранного спрятан. */
    fun hidden(i: Int): Boolean = i in hiddenAt.indices && hiddenAt[i]

    /** Место в набранном → место на экране. */
    fun toShown(i: Int): Int = shownAt[i.coerceIn(0, shownAt.lastIndex)]

    /** Место на экране → место в набранном. */
    fun toSource(t: Int): Int = sourceAt[t.coerceIn(0, sourceAt.lastIndex)]

    companion object {
        fun of(source: String, spans: List<TypingSpan>): HiddenMarks {
            val n = source.length
            val hidden = BooleanArray(n)
            val swap = HashMap<Int, Char>()
            for (s in spans) {
                if (s.style != TypingStyle.MARKER) continue
                val a = s.start.coerceIn(0, n)
                val b = s.end.coerceIn(a, n)
                val sign = source.substring(a, b)
                when {
                    wholeLine(source, a, b) -> Unit
                    sign.length == 2 && sign[1] == ' ' && sign[0] in "-*+" -> swap[a] = '•'
                    else -> for (i in a until b) hidden[i] = true
                }
            }
            val shownAt = IntArray(n + 1)
            val shown = StringBuilder()
            for (i in 0 until n) {
                shownAt[i] = shown.length
                if (!hidden[i]) shown.append(swap[i] ?: source[i])
            }
            shownAt[n] = shown.length
            val sourceAt = IntArray(shown.length + 1) { -1 }
            for (i in 0..n) if (sourceAt[shownAt[i]] == -1) sourceAt[shownAt[i]] = i
            for (t in sourceAt.indices) {
                var i = sourceAt[t]
                if (i == 0 || source.getOrNull(i - 1) == '\n') while (i < n && hidden[i]) i++
                sourceAt[t] = i
            }
            return HiddenMarks(shown.toString(), hidden, shownAt, sourceAt)
        }

        private fun wholeLine(source: String, a: Int, b: Int): Boolean {
            if (b - a < 3) return false
            val lineStart = source.lastIndexOf('\n', a - 1) + 1
            val lineEnd = source.indexOf('\n', b).let { if (it < 0) source.length else it }
            return source.substring(lineStart, a).isBlank() && source.substring(b, lineEnd).isBlank()
        }
    }
}

/**
 * Набор «как в Word» поверх знаков: Ж без выделения не ставит пару знаков у курсора, а ждёт буквы;
 * повторное нажатие в конце жирного закрывает его; стёртый спрятанный знак стирает видимую букву.
 */
internal object MarkEdits {
    private val INLINE = setOf(TypingStyle.BOLD, TypingStyle.ITALIC, TypingStyle.UNDERLINE, TypingStyle.STRIKE, TypingStyle.HIGHLIGHT, TypingStyle.CODE)

    fun styleOf(kind: InlineKind): TypingStyle = TypingStyle.valueOf(kind.name)

    fun kindOf(style: TypingStyle): InlineKind? = InlineKind.entries.firstOrNull { it.name == style.name }

    /** Оформление строки там, где курсор [c]: куски, которые его охватывают, — набор продолжит их. */
    fun activeAt(spans: List<TypingSpan>, c: Int): Set<InlineKind> =
        spans.filter { it.style in INLINE && it.start < c && c <= it.end }.mapNotNull { kindOf(it.style) }.toSet()

    /** Оформление, охватывающее выделенное [s]…[e] целиком. */
    fun covering(spans: List<TypingSpan>, s: Int, e: Int): Set<InlineKind> =
        spans.filter { it.style in INLINE && it.start <= s && e <= it.end }.mapNotNull { kindOf(it.style) }.toSet()

    /**
     * Курсор [c] в самом конце оформленных кусков: какие закрываются и где кончаются их знаки.
     * `null` — курсор не в конце оформленного.
     */
    fun closingAt(spans: List<TypingSpan>, c: Int): Pair<Set<TypingStyle>, Int>? {
        val closed = spans.filter { it.style in INLINE && it.start < c && it.end == c }.map { it.style }.toSet()
        if (closed.isEmpty()) return null
        var at = c
        while (true) {
            val m = spans.firstOrNull { it.style == TypingStyle.MARKER && it.start == at && it.end > at } ?: break
            at = m.end
        }
        return closed to at
    }

    /** Стёрт спрятанный знак [p]: стереть ближайшую видимую букву в ту же сторону; нет её — ничего. */
    fun deleteVisible(before: String, marks: HiddenMarks, p: Int, cursor: Int, back: Boolean): TextEdit? {
        val q = if (back) (p - 1 downTo 0).firstOrNull { !marks.hidden(it) } else (p + 1 until before.length).firstOrNull { !marks.hidden(it) }
        q ?: return null
        val text = before.removeRange(q, q + 1)
        val at = if (back) q else cursor
        return TextEdit(text, at, at)
    }

    /**
     * После стирания курсор между пустой парой знаков (`**|**`): пару убрать, а её вид — ждать
     * следующей буквы. Иначе пара осталась бы видна знаками.
     */
    fun emptyPair(text: String, c: Int): Pair<TextEdit, InlineKind>? {
        for (kind in InlineKind.entries.sortedByDescending { it.sign.length }) {
            val sign = kind.sign
            val l = sign.length
            if (c >= l && c + l <= text.length && text.regionMatches(c - l, sign, 0, l) && text.regionMatches(c, sign, 0, l)) {
                return TextEdit(text.removeRange(c, c + l).removeRange(c - l, c), c - l, c - l) to kind
            }
        }
        return null
    }
}

/**
 * Вид поля редактора и строки ввода (ADR-0048, «Знаки md» включены): закрытая пара и знак строки спрятаны,
 * текст оформлен; открывающий знак без пары — серым; заголовок — крупнее.
 */
@Composable
internal fun rememberEditorView(marks: TypingMarks?): VisualTransformation {
    val colors = Tima.colors
    if (marks == null) return VisualTransformation.None
    val marker = colors.text3
    val quote = colors.text2
    val highlight = colors.activity.copy(alpha = 0.35f)
    val code = colors.functional
    val tag = colors.confirmed
    return remember(marks, marker, quote, highlight, code, tag) {
        VisualTransformation { text ->
            val spans = marks.of(text.text)
            val view = HiddenMarks.of(text.text, spans)
            val styled = buildAnnotatedString {
                append(view.shown)
                for (span in spans) {
                    val style = when (span.style) {
                        TypingStyle.MARKER, TypingStyle.OPEN -> SpanStyle(color = marker)
                        TypingStyle.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                        TypingStyle.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                        TypingStyle.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
                        TypingStyle.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        TypingStyle.HIGHLIGHT -> SpanStyle(background = highlight)
                        TypingStyle.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = code)
                        TypingStyle.MENTION, TypingStyle.HASHTAG -> SpanStyle(color = tag)
                        TypingStyle.HEADING -> SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = TimaType.sz3)
                        TypingStyle.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic, color = quote)
                    }
                    val start = view.toShown(span.start)
                    val end = view.toShown(span.end).coerceAtLeast(start)
                    if (end > start) addStyle(style, start, end)
                }
            }
            TransformedText(
                styled,
                object : OffsetMapping {
                    override fun originalToTransformed(offset: Int): Int = view.toShown(offset)
                    override fun transformedToOriginal(offset: Int): Int = view.toSource(offset)
                },
            )
        }
    }
}
