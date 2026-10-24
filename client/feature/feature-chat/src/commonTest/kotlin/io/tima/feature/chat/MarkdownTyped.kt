package io.tima.feature.chat

import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.LineKind
import io.tima.domain.chat.Typed
import io.tima.domain.chat.TypedEdits
import io.tima.domain.chat.TypedSource
import io.tima.domain.chat.TypedSpan
import io.tima.domain.chat.TypingSpan

/**
 * Подделка перевода «набранное ↔ без знаков» для тестов модели: пары знаков и знаки строки, без
 * экранирования. Настоящий — `TypedOverQuickMarkdown` в `core-encryption`, его сверяют свои тесты.
 */
internal object MarkdownTyped : TypedSource {
    private val SIGNS = listOf(
        "### " to LineKind.HEADING3, "## " to LineKind.HEADING2, "# " to LineKind.HEADING1,
        "    - " to LineKind.LIST3, "  - " to LineKind.LIST2, "- " to LineKind.LIST1, "--> " to LineKind.QUOTE,
    )
    private val PAIRS = listOf(InlineKind.BOLD, InlineKind.UNDERLINE, InlineKind.STRIKE, InlineKind.HIGHLIGHT, InlineKind.ITALIC, InlineKind.CODE)

    override fun read(source: String): Typed {
        val text = StringBuilder()
        val kinds = mutableListOf<LineKind>()
        val spans = mutableListOf<TypedSpan>()
        source.split('\n').forEachIndexed { i, raw ->
            if (i > 0) text.append('\n')
            val sign = SIGNS.firstOrNull { raw.startsWith(it.first) }
            kinds += sign?.second ?: LineKind.PARAGRAPH
            val line = raw.removePrefix(sign?.first.orEmpty())
            val base = text.length
            val open = HashMap<InlineKind, Int>()
            var k = 0
            val out = StringBuilder()
            while (k < line.length) {
                val kind = PAIRS.firstOrNull { line.startsWith(it.sign, k) }
                if (kind != null && (kind in open || line.indexOf(kind.sign, k + kind.sign.length) > k + kind.sign.length)) {
                    val from = open.remove(kind)
                    if (from != null) spans += TypedSpan(kind, base + from, base + out.length) else open[kind] = out.length
                    k += kind.sign.length
                    continue
                }
                out.append(line[k++])
            }
            text.append(out)
        }
        return TypedEdits.normalized(Typed(text.toString(), kinds, spans))
    }

    override fun write(typed: Typed): String {
        var base = 0
        return typed.text.split('\n').mapIndexed { i, line ->
            val from = base
            base += line.length + 1
            val kind = typed.lines.getOrElse(i) { LineKind.PARAGRAPH }
            val mine = typed.spans.filter { it.start >= from && it.end <= from + line.length }
            val out = StringBuilder(SIGNS.firstOrNull { it.second == kind }?.first.orEmpty())
            for (pos in 0..line.length) {
                for (s in mine.filter { it.end - from == pos }.reversed()) out.append(s.kind.sign)
                for (s in mine.filter { it.start - from == pos }) out.append(s.kind.sign)
                if (pos < line.length) out.append(line[pos])
            }
            out.toString()
        }.joinToString("\n")
    }

    /** Вид строки ввода: только пары `**…**` — знаки серым, между ними жирный (ADR-0053). */
    override fun of(text: String): List<TypingSpan> = Regex("""\*\*([^*]+)\*\*""").findAll(text).flatMap { m ->
        val a = m.range.first
        val e = m.range.last + 1
        listOf(
            TypingSpan(io.tima.domain.chat.TypingStyle.MARKER, a, a + 2),
            TypingSpan(io.tima.domain.chat.TypingStyle.BOLD, a + 2, e - 2),
            TypingSpan(io.tima.domain.chat.TypingStyle.MARKER, e - 2, e),
        )
    }.toList()
}
