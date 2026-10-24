package io.tima.domain.chat

/**
 * Блоки куска-текста (заказчик 2026-10-24): редактор показывает кусок так же, как его увидит
 * получатель, — а получатель видит текст куска блоками: абзац (строки подряд), каждая строка-заголовок,
 * цитата, список, код, разделитель. Стиль задаётся **блоку своего вида**: кусок режется на месте блока
 * под курсором, и у блока свой стиль, как в сообщении. Соседние куски одного стиля редактор склеивает
 * сам ([ComposedLayout.joinTexts]) — отрезанное без нового стиля срастается обратно.
 */
object ComposedBlocks {

    /** Кусок разрезан: документ, номер куска-блока и курсор в нём. */
    data class Cut(val doc: ComposedDoc, val item: Int, val caret: Int)

    /** Строки блока, в котором строка [line]: `from` включительно, `to` — нет. */
    fun blockOf(t: Typed, line: Int): IntRange {
        val n = t.lines.size
        val at = line.coerceIn(0, n - 1)
        val kind = group(t.lines[at])
        // Заголовок и разделитель — каждая строка своим блоком.
        if (kind == null) return at until at + 1
        var from = at
        while (from > 0 && group(t.lines[from - 1]) == kind) from--
        var to = at + 1
        while (to < n && group(t.lines[to]) == kind) to++
        return from until to
    }

    /** Строки, с которых начинается новый блок, кроме первой, — между ними у получателя зазор плашек. */
    fun starts(t: Typed): List<Int> {
        val out = mutableListOf<Int>()
        var line = 0
        while (line < t.lines.size) {
            val next = blockOf(t, line).last + 1
            if (next < t.lines.size) out += next
            line = next
        }
        return out
    }

    /** Строки одного блока: абзац, цитата, список (любой ступени), код; `null` — строка сама по себе блок. */
    private fun group(k: LineKind): String? = when (k) {
        LineKind.PARAGRAPH -> "p"
        LineKind.QUOTE -> "q"
        LineKind.LIST1, LineKind.LIST2, LineKind.LIST3 -> "l"
        LineKind.CODE -> "c"
        LineKind.HEADING1, LineKind.HEADING2, LineKind.HEADING3, LineKind.DIVIDER -> null
    }

    /**
     * Отрезать блок, где курсор [caret] куска [item]: до него, сам блок, после — кусками со стилем куска.
     * Блок и есть весь кусок — документ как был.
     */
    fun cut(doc: ComposedDoc, item: Int, caret: Int, source: TypedSource): Cut {
        val text = doc.items.getOrNull(item) as? ComposedItem.Text ?: return Cut(doc, item, caret)
        val t = source.read(text.source)
        val block = blockOf(t, t.lineAt(caret))
        if (block.first == 0 && block.last == t.lines.lastIndex) return Cut(doc, item, caret)
        val pieces = buildList {
            if (block.first > 0) add(ComposedLists.slice(t, 0, block.first))
            add(ComposedLists.slice(t, block.first, block.last + 1))
            if (block.last + 1 < t.lines.size) add(ComposedLists.slice(t, block.last + 1, t.lines.size))
        }.map { ComposedItem.Text(source.write(it), text.style) }
        val items = doc.items.subList(0, item) + pieces + doc.items.subList(item + 1, doc.items.size)
        val at = item + if (block.first > 0) 1 else 0
        return Cut(doc.copy(items = items), at, caret - t.lineStart(block.first))
    }
}
