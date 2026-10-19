package io.tima.core.encryption

import io.tima.crypto.EntityType
import io.tima.crypto.TypedLines
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.LineKind
import io.tima.domain.chat.Typed
import io.tima.domain.chat.TypedSource
import io.tima.domain.chat.TypedSpan
import io.tima.domain.chat.TypingSpan

/**
 * Набранное без знаков ↔ текст со знаками (ADR-0048 §1) — тем же разбором, что уходит в сообщение:
 * редактор и строка ввода правят строки и отрезки, а хранят и отправляют набранное.
 */
object TypedOverQuickMarkdown : TypedSource {

    private val TYPES = mapOf(
        InlineKind.BOLD to EntityType.BOLD,
        InlineKind.ITALIC to EntityType.ITALIC,
        InlineKind.UNDERLINE to EntityType.UNDERLINE,
        InlineKind.STRIKE to EntityType.STRIKETHROUGH,
        InlineKind.HIGHLIGHT to EntityType.HIGHLIGHT,
        InlineKind.CODE to EntityType.CODE,
    )
    private val KINDS = TYPES.entries.associate { (k, v) -> v to k }

    override fun read(source: String): Typed {
        val lines = TypedLines.read(source)
        val text = StringBuilder()
        val spans = mutableListOf<TypedSpan>()
        lines.forEachIndexed { i, l ->
            if (i > 0) text.append('\n')
            val base = text.length
            for (s in l.spans) KINDS[s.type]?.let { spans += TypedSpan(it, base + s.start, base + s.end) }
            text.append(l.text)
        }
        return Typed(text.toString(), lines.map { LineKind.valueOf(it.kind.name) }, spans)
    }

    override fun write(typed: Typed): String {
        val texts = typed.text.split('\n')
        var base = 0
        val lines = texts.mapIndexed { i, text ->
            val from = base
            val to = base + text.length
            base = to + 1
            TypedLines.Line(
                TypedLines.Kind.valueOf(typed.lines.getOrElse(i) { LineKind.PARAGRAPH }.name),
                text,
                typed.spans.filter { it.start >= from && it.end <= to }.map { TypedLines.Span(TYPES.getValue(it.kind), it.start - from, it.end - from) },
            )
        }
        return TypedLines.write(lines)
    }

    /** Вид поля: открытые знаки, упоминания и метки — по видимому тексту. */
    override fun of(text: String): List<TypingSpan> = TypingMarksOverQuickMarkdown.of(text)
}
