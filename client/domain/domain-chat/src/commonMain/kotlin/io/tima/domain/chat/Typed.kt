package io.tima.domain.chat

/** Вид строки набранного (ADR-0048): то, что прежде задавал знак в её начале. */
enum class LineKind {
    PARAGRAPH, HEADING1, HEADING2, HEADING3, QUOTE, LIST1, LIST2, LIST3, CODE, DIVIDER;

    /** Как отмечен в «Абзац ▾». */
    val paragraph: ParagraphKind
        get() = when (this) {
            HEADING1 -> ParagraphKind.HEADING1
            HEADING2 -> ParagraphKind.HEADING2
            HEADING3 -> ParagraphKind.HEADING3
            QUOTE -> ParagraphKind.QUOTE
            LIST1 -> ParagraphKind.LIST
            LIST2 -> ParagraphKind.LIST2
            LIST3 -> ParagraphKind.LIST3
            CODE -> ParagraphKind.CODE
            PARAGRAPH, DIVIDER -> ParagraphKind.PARAGRAPH
        }

    companion object {
        fun of(kind: ParagraphKind): LineKind = when (kind) {
            ParagraphKind.PARAGRAPH -> PARAGRAPH
            ParagraphKind.HEADING1 -> HEADING1
            ParagraphKind.HEADING2 -> HEADING2
            ParagraphKind.HEADING3 -> HEADING3
            ParagraphKind.QUOTE -> QUOTE
            ParagraphKind.LIST -> LIST1
            ParagraphKind.LIST2 -> LIST2
            ParagraphKind.LIST3 -> LIST3
            ParagraphKind.CODE -> CODE
        }
    }
}

/** Отрезок оформления набранного: [start] включительно, [end] — нет; через перевод строки не идёт. */
data class TypedSpan(val kind: InlineKind, val start: Int, val end: Int)

/**
 * Набранное без знаков (ADR-0048 §1): текст, вид каждой его строки и отрезки оформления. Так его
 * держат поле редактора и строка ввода — курсор и стирание идут по видимым буквам. [lines] — по
 * одному на каждую строку [text].
 */
data class Typed(val text: String, val lines: List<LineKind>, val spans: List<TypedSpan> = emptyList()) {

    /** Номер строки, в которой место [pos]. */
    fun lineAt(pos: Int): Int {
        var n = 0
        for (i in 0 until pos.coerceIn(0, text.length)) if (text[i] == '\n') n++
        return n
    }

    /** Где начинается строка [line]. */
    fun lineStart(line: Int): Int {
        var n = 0
        if (line <= 0) return 0
        for (i in text.indices) if (text[i] == '\n' && ++n == line) return i + 1
        return text.length
    }

    fun lineEnd(line: Int): Int = text.indexOf('\n', lineStart(line)).let { if (it < 0) text.length else it }

    companion object {
        fun plain(text: String) = Typed(text, List(text.count { it == '\n' } + 1) { LineKind.PARAGRAPH })
    }
}

/**
 * Набранный текст со знаками ↔ [Typed] — тем же разбором, что уходит в сообщение (`core-encryption`).
 * Знаки — только способ набора (ADR-0048): хранится текст со знаками, правится — без них.
 */
interface TypedSource : TypingMarks {
    fun read(source: String): Typed
    fun write(typed: Typed): String
}

/** Без разбора: текст как есть — где знаков нет (тесты, место без разметки). */
object PlainTyped : TypedSource {
    override fun read(source: String) = Typed.plain(source)
    override fun write(typed: Typed) = typed.text
    override fun of(text: String) = emptyList<TypingSpan>()
}

/**
 * Правка набранного (ADR-0048): поле сменило текст — отрезки и виды строк идут следом; кнопки
 * ставят отрезок и вид строки. Чистые функции: одно и то же у редактора и строки ввода.
 */
object TypedEdits {

    /** Итог правки; [cursor] — куда встать, если набранное переделано (знаки стали оформлением). */
    data class Change(val typed: Typed, val cursor: Int? = null)

    /** Знаки строки, превращающиеся сразу (ADR-0048 §1). */
    private val LINE_SIGNS = listOf(
        "### " to LineKind.HEADING3, "## " to LineKind.HEADING2, "# " to LineKind.HEADING1,
        "    - " to LineKind.LIST3, "  - " to LineKind.LIST2, "- " to LineKind.LIST1, "--> " to LineKind.QUOTE,
    )

    /** Знаки пары — длинные первыми: `**` раньше `*`. */
    private val PAIRS = listOf(InlineKind.BOLD, InlineKind.UNDERLINE, InlineKind.STRIKE, InlineKind.HIGHLIGHT, InlineKind.ITALIC, InlineKind.CODE)

    /**
     * Поле сменило текст на [after], курсор — [cursor]. Набранное внутри и в конце оформленного
     * продолжает его, в начале — нет (Word); [pending] — что наденется на набранное вместо этого
     * (Ж без выделения). [convert] — «Знаки md» включены: закрытая пара и знак строки сразу
     * становятся оформлением; [lineSigns] — знаки строки (выключены там, где вида строки нет);
     * [listSigns] — и знак пункта (в содержимом пункта списка его нет: вложенный — ступенью пункта);
     * [untitled] — заголовок пункта, который встаёт над пунктом ступени без родителя.
     */
    fun change(
        before: Typed,
        after: String,
        cursor: Int,
        pending: Set<InlineKind>?,
        convert: Boolean,
        lineSigns: Boolean = true,
        listSigns: Boolean = true,
        untitled: String = "",
    ): Change {
        val old = before.text
        val (p, oldEnd, newEnd) = diff(old, after, cursor)
        val inserted = after.substring(p, newEnd)
        val deleted = old.substring(p, oldEnd)
        val upperLine = before.lineAt(p)

        // Стёрт перевод строки перед оформленной строкой — сначала снимается вид строки (Word).
        if (deleted == "\n" && inserted.isEmpty()) {
            val lower = upperLine + 1
            if (before.lines.getOrNull(lower).let { it != null && it != LineKind.PARAGRAPH && it != LineKind.DIVIDER }) {
                return Change(before.copy(lines = before.lines.toMutableList().apply { set(lower, LineKind.PARAGRAPH) }), p + 1)
            }
        }
        // Перевод строки в пустом пункте или цитате — список кончился, строка стала абзацем.
        if (inserted == "\n" && deleted.isEmpty()) {
            val kind = before.lines.getOrNull(upperLine)
            val empty = before.lineStart(upperLine) == before.lineEnd(upperLine)
            if (empty && kind != null && kind != LineKind.PARAGRAPH && kind != LineKind.CODE && kind != LineKind.DIVIDER) {
                return Change(before.copy(lines = before.lines.toMutableList().apply { set(upperLine, LineKind.PARAGRAPH) }), p)
            }
        }

        // Виды строк: сливаемые строки берут вид верхней; новые — её продолжение.
        val lowerLine = before.lineAt(oldEnd)
        val upper = before.lines.getOrElse(upperLine) { LineKind.PARAGRAPH }
        val breaks = inserted.count { it == '\n' }
        val atLineStart = p == before.lineStart(upperLine)
        val middle = if (atLineStart && deleted.isEmpty() && breaks > 0 && inserted.all { it == '\n' }) {
            // Перевод строки в её начале: строка уходит вниз со своим видом, сверху — пустые абзацы.
            List(breaks) { LineKind.PARAGRAPH } + upper
        } else {
            List(breaks + 1) { i -> if (i == 0) upper else continuation(upper) }
        }
        val lines = before.lines.subList(0, upperLine) + middle + before.lines.subList((lowerLine + 1).coerceAtMost(before.lines.size), before.lines.size)

        // Отрезки: стёртое вырезано, вставленное — оформлено, как место перед ним (или как ждёт).
        val inherited = pending ?: if (deleted.isEmpty()) active(before, p) else covering(before, p, oldEnd)
        var spans = replaced(before.spans, p, oldEnd, inserted.length)
        if (inserted.isNotEmpty()) for (kind in InlineKind.entries) {
            spans = if (kind in inherited) spans + TypedSpan(kind, p, p + inserted.length) else without(spans, kind, p, p + inserted.length)
        }
        var typed = normalized(Typed(after, lines, spans))

        if (!convert || deleted.isNotEmpty() || inserted.isEmpty() || '\n' in inserted) return Change(typed)
        val c = p + inserted.length
        if (lineSigns) lineSign(typed, c, listSigns, untitled)?.let { return it }
        return pairClosed(typed, c) ?: Change(typed)
    }

    /** Знак строки набран в её начале — строка получает вид, знак уходит. */
    private fun lineSign(t: Typed, c: Int, listSigns: Boolean, untitled: String): Change? {
        val line = t.lineAt(c)
        if (t.lines[line] != LineKind.PARAGRAPH) return null
        val start = t.lineStart(line)
        val end = t.lineEnd(line)
        val head = t.text.substring(start, c)
        val whole = t.text.substring(start, end)
        LINE_SIGNS.firstOrNull { it.first == head && (listSigns || it.second !in LISTS) }?.let { (sign, kind) ->
            val cut = remove(t, start, start + sign.length)
            val (next, shift) = parents(cut.copy(lines = cut.lines.toMutableList().apply { set(line, kind) }), untitled)
            return Change(next, shift(start))
        }
        if (c == end && whole == "```") {
            val cut = remove(t, start, end)
            return Change(cut.copy(lines = cut.lines.toMutableList().apply { set(line, LineKind.CODE) }), start)
        }
        if (c == end && whole == "---") {
            // Разделитель — строка без текста; писать дальше — с новой строки под ним.
            val cut = remove(t, start, end)
            val text = cut.text.substring(0, start) + "\n" + cut.text.substring(start)
            val lines = cut.lines.toMutableList().apply { set(line, LineKind.DIVIDER); add(line + 1, LineKind.PARAGRAPH) }
            return Change(normalized(Typed(text, lines, replaced(cut.spans, start, start, 1))), start + 1)
        }
        return null
    }

    /** Набран закрывающий знак пары — пара стала оформлением, знаки ушли. */
    private fun pairClosed(t: Typed, c: Int): Change? {
        val line = t.lineAt(c)
        if (t.lines[line] == LineKind.CODE) return null
        val ls = t.lineStart(line)
        val text = t.text
        fun inCode(i: Int) = t.spans.any { it.kind == InlineKind.CODE && it.start <= i && i < it.end }
        for (kind in PAIRS) {
            val sign = kind.sign
            val l = sign.length
            val q = c - l
            if (q - 1 < ls || !text.regionMatches(q, sign, 0, l)) continue
            val prev = text[q - 1]
            if (prev.isWhitespace() || inCode(q) || inCode(q - 1)) continue
            // Одиночная звёздочка — не половина двойной.
            if (kind == InlineKind.ITALIC && prev == '*') continue
            var o = q - l - 1
            while (o >= ls) {
                val fits = text.regionMatches(o, sign, 0, l) && !text[o + l].isWhitespace() && !inCode(o) &&
                    (kind != InlineKind.ITALIC || (text.getOrNull(o - 1) != '*' && text.getOrNull(o + 1) != '*')) &&
                    (kind != InlineKind.BOLD || text.getOrNull(o - 1) != '*')
                if (fits) break
                if (kind == InlineKind.CODE && text[o] == '`') { o = -1; break }
                o--
            }
            if (o < ls) continue
            val inner = o + l until q
            var cut = remove(remove(t, q, c), o, o + l)
            var spans = cut.spans + TypedSpan(kind, o, o + inner.count())
            if (kind == InlineKind.CODE) spans = spans.filter { it.kind == InlineKind.CODE || it.end <= o || it.start >= o + inner.count() }
            cut = normalized(cut.copy(spans = spans))
            return Change(cut, c - 2 * l)
        }
        return null
    }

    /** Вырезать [from]…[to] из одной строки (знаки внутри строки — перевода строки там нет). */
    private fun remove(t: Typed, from: Int, to: Int): Typed =
        normalized(Typed(t.text.removeRange(from, to), t.lines, replaced(t.spans, from, to, 0)))

    /** Строка после перевода в конце строки [kind]. */
    private fun continuation(kind: LineKind): LineKind = when (kind) {
        LineKind.HEADING1, LineKind.HEADING2, LineKind.HEADING3, LineKind.DIVIDER -> LineKind.PARAGRAPH
        else -> kind
    }

    /**
     * Что поменялось: начало, конец стёртого в прежнем, конец вставленного в новом. Курсор после
     * правки решает, где набрано, когда соседние буквы одинаковы («а|а» → «аа|а»).
     */
    private fun diff(old: String, new: String, cursor: Int): Triple<Int, Int, Int> {
        val d = new.length - old.length
        if (d > 0 && cursor - d >= 0 && cursor <= new.length &&
            new.regionMatches(0, old, 0, cursor - d) && new.regionMatches(cursor, old, cursor - d, old.length - (cursor - d))
        ) return Triple(cursor - d, cursor - d, cursor)
        if (d < 0 && cursor >= 0 && cursor - d <= old.length &&
            old.regionMatches(0, new, 0, cursor) && old.regionMatches(cursor - d, new, cursor, new.length - cursor)
        ) return Triple(cursor, cursor - d, cursor)
        var p = 0
        while (p < old.length && p < new.length && old[p] == new[p]) p++
        var q = 0
        while (q < old.length - p && q < new.length - p && old[old.length - 1 - q] == new[new.length - 1 - q]) q++
        return Triple(p, old.length - q, new.length - q)
    }

    /** [p]…[oldEnd] заменено [len] знаками: отрезки вокруг сдвинуты, внутри стёртого — сжаты, в начале вставки — не растут. */
    private fun replaced(spans: List<TypedSpan>, p: Int, oldEnd: Int, len: Int): List<TypedSpan> {
        val d = oldEnd - p
        fun cut(x: Int) = when {
            x <= p -> x
            x >= oldEnd -> x - d
            else -> p
        }
        return spans.mapNotNull { s ->
            val a = cut(s.start)
            val b = cut(s.end)
            if (b <= a && d > 0) return@mapNotNull null
            TypedSpan(s.kind, if (a >= p) a + len else a, if (b > p) b + len else b)
        }
    }

    /** Убрать вид [kind] с [from]…[to]. */
    private fun without(spans: List<TypedSpan>, kind: InlineKind, from: Int, to: Int): List<TypedSpan> = spans.flatMap { s ->
        if (s.kind != kind || s.end <= from || s.start >= to) listOf(s)
        else listOfNotNull(
            TypedSpan(kind, s.start, from).takeIf { from > s.start },
            TypedSpan(kind, to, s.end).takeIf { s.end > to },
        )
    }

    /** Оформление там, где курсор [c]: отрезки, охватывающие букву перед ним, — набор продолжит их. */
    fun active(t: Typed, c: Int): Set<InlineKind> = t.spans.filter { it.start < c && c <= it.end }.map { it.kind }.toSet()

    /** Оформление, охватывающее [s]…[e] целиком. */
    fun covering(t: Typed, s: Int, e: Int): Set<InlineKind> = t.spans.filter { it.start <= s && e <= it.end }.map { it.kind }.toSet()

    /** Ж, К, Ч, З, М, код на выделенном: есть у всего выделенного — снять, иначе — поставить. */
    fun toggle(t: Typed, s: Int, e: Int, kind: InlineKind): Typed {
        if (e <= s) return t
        val spans = if (kind in covering(t, s, e)) without(t.spans, kind, s, e) else t.spans + TypedSpan(kind, s, e)
        return normalized(t.copy(spans = spans))
    }

    private val LISTS = listOf(LineKind.LIST1, LineKind.LIST2, LineKind.LIST3)

    /**
     * Пункта ступени 2 без пункта 1 над ним (3 без 2) не бывает (заказчик 2026-10-19): над ним встаёт
     * пункт [untitled] — обычный текст, правится как любой. Пусто [untitled] — ничего не вставляется.
     * Второе — куда сдвинулось место прежнего текста.
     */
    fun parents(t: Typed, untitled: String): Pair<Typed, (Int) -> Int> {
        if (untitled.isEmpty()) return t to { it }
        val inserts = mutableListOf<Pair<Int, List<LineKind>>>()
        var prev = 0
        for (i in t.lines.indices) {
            val d = LISTS.indexOf(t.lines[i]) + 1
            if (d == 0) {
                prev = 0
                continue
            }
            if (d > prev + 1) inserts += i to (prev + 1 until d).map { LISTS[it - 1] }
            prev = d
        }
        if (inserts.isEmpty()) return t to { it }
        var text = t.text
        val lines = t.lines.toMutableList()
        var spans = t.spans
        val shifts = mutableListOf<Pair<Int, Int>>()
        // С конца: места выше остаются прежними.
        for ((line, kinds) in inserts.reversed()) {
            val at = t.lineStart(line)
            val piece = kinds.joinToString("") { untitled + "\n" }
            text = text.substring(0, at) + piece + text.substring(at)
            lines.addAll(line, kinds)
            spans = replaced(spans, at, at, piece.length)
            shifts += at to piece.length
        }
        return normalized(Typed(text, lines, spans)) to { pos -> pos + shifts.filter { pos >= it.first }.sumOf { it.second } }
    }

    /** «Абзац ▾»: строки с [s] по [e] получают вид [kind]; коду — без оформления внутри. */
    fun lines(t: Typed, s: Int, e: Int, kind: LineKind): Typed {
        val a = t.lineAt(minOf(s, e))
        val b = t.lineAt(maxOf(s, e))
        val lines = t.lines.mapIndexed { i, k -> if (i in a..b) kind else k }
        val from = t.lineStart(a)
        val to = t.lineEnd(b)
        val spans = if (kind == LineKind.CODE) t.spans.filter { it.end <= from || it.start >= to } else t.spans
        return normalized(Typed(t.text, lines, spans))
    }

    /**
     * Вставить [piece] вместо [s]…[e] (символ, вставка из буфера): первая строка вставки — в строку
     * курсора, остальные — со своим видом. Знаки в тексте вставки — текст. Курсор — за вставкой.
     */
    fun insert(t: Typed, s: Int, e: Int, piece: Typed): Change {
        val a = minOf(s, e).coerceIn(0, t.text.length)
        val b = maxOf(s, e).coerceIn(0, t.text.length)
        val text = t.text.substring(0, a) + piece.text + t.text.substring(b)
        val la = t.lineAt(a)
        val lb = t.lineAt(b)
        val middle = listOf(t.lines[la]) + piece.lines.drop(1)
        val lines = t.lines.subList(0, la) + middle + t.lines.subList(lb + 1, t.lines.size)
        val spans = replaced(t.spans, a, b, piece.text.length) + piece.spans.map { it.copy(start = it.start + a, end = it.end + a) }
        return Change(normalized(Typed(text, lines, spans)), a + piece.text.length)
    }

    /**
     * Порядок: строк столько, сколько строк в тексте; разделитель без текста; отрезки — в границах,
     * не через перевод строки и не в коде-строке, одного вида слиты.
     */
    fun normalized(t: Typed): Typed {
        val count = t.text.count { it == '\n' } + 1
        val lines = MutableList(count) { i -> t.lines.getOrElse(i) { LineKind.PARAGRAPH } }
        var start = 0
        val codeLines = mutableListOf<IntRange>()
        for (i in 0 until count) {
            val end = t.text.indexOf('\n', start).let { if (it < 0) t.text.length else it }
            if (lines[i] == LineKind.DIVIDER && end > start) lines[i] = LineKind.PARAGRAPH
            if (lines[i] == LineKind.CODE) codeLines += start until end
            start = end + 1
        }
        val len = t.text.length
        val pieces = t.spans.flatMap { s ->
            val a = s.start.coerceIn(0, len)
            val b = s.end.coerceIn(0, len)
            val out = mutableListOf<TypedSpan>()
            var from = a
            while (from < b) {
                val nl = t.text.indexOf('\n', from).let { if (it < 0 || it >= b) b else it }
                if (nl > from) out += TypedSpan(s.kind, from, nl)
                from = nl + 1
            }
            out
        }.filter { s -> codeLines.none { s.start >= it.first && s.end <= it.last + 1 } }
        val merged = pieces.groupBy { it.kind }.flatMap { (_, list) ->
            val out = mutableListOf<TypedSpan>()
            for (s in list.sortedBy { it.start }) {
                val last = out.lastOrNull()
                if (last != null && s.start <= last.end) out[out.lastIndex] = last.copy(end = maxOf(last.end, s.end)) else out += s
            }
            out
        }.sortedWith(compareBy({ it.start }, { -it.end }, { it.kind.ordinal }))
        return Typed(t.text, lines, merged)
    }
}
