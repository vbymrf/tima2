package io.tima.crypto

/**
 * Набранное без знаков (ADR-0048 §1): строки — вид и текст — и отрезки оформления в них. Так его
 * держат редактор и строка ввода: курсор и стирание идут по видимым буквам, Ж/К/Ч ставят отрезок.
 *
 * Хранится и уходит набранное по-прежнему текстом со знаками ([write] → [QuickMarkdown.parse]):
 * черновики, отправка и правка по узлам не меняются. Знак, набранный как текст, в записи
 * экранирован — знаком он не станет.
 */
object TypedLines {

    /** Вид строки — то, что прежде задавал знак в её начале. */
    enum class Kind { PARAGRAPH, HEADING1, HEADING2, HEADING3, QUOTE, LIST1, LIST2, LIST3, CODE, DIVIDER }

    /** Отрезок в знаках строки; виды — [QuickMarkdown.Inline.PAIRED]. */
    data class Span(val type: EntityType, val start: Int, val end: Int)

    data class Line(val kind: Kind, val text: String, val spans: List<Span> = emptyList())

    private val HEADINGS = listOf(Kind.HEADING1, Kind.HEADING2, Kind.HEADING3)
    private val LISTS = listOf(Kind.LIST1, Kind.LIST2, Kind.LIST3)

    /** Набранное со знаками → строки. Таблица знаками (`|`) читается текстом: таблицы правит редактор рядами. */
    fun read(source: String): List<Line> {
        val raw = source.replace("\r\n", "\n").replace("\t", "    ").split('\n')
        val out = mutableListOf<Line>()
        var i = 0
        while (i < raw.size) {
            val line = raw[i]
            val trimmed = line.trimStart()
            if (trimmed.trimEnd() == "```") {
                var j = i + 1
                while (j < raw.size && raw[j].trim() != "```") j++
                if (j < raw.size) {
                    if (j == i + 1) out += Line(Kind.CODE, "")
                    for (k in i + 1 until j) out += Line(Kind.CODE, raw[k])
                    i = j + 1
                    continue
                }
            }
            when {
                trimmed.trimEnd() == "---" || trimmed.trimEnd() == "***" -> out += Line(Kind.DIVIDER, "")
                QuickMarkdown.HEADING.matches(trimmed) -> {
                    val m = QuickMarkdown.HEADING.find(trimmed)!!
                    out += inline(HEADINGS[m.groupValues[1].length - 1], m.groupValues[2])
                }
                trimmed.startsWith("-->") -> out += inline(Kind.QUOTE, trimmed.removePrefix("-->").removePrefix(" "))
                QuickMarkdown.LIST_ITEM.matches(line) -> {
                    // Глубина — как у разбора: вложен в ближайший выше с меньшим отступом, не глубже третьего.
                    val stack = ArrayDeque<Pair<Int, Int>>()
                    while (i < raw.size && QuickMarkdown.LIST_ITEM.matches(raw[i])) {
                        val m = QuickMarkdown.LIST_ITEM.find(raw[i])!!
                        val indent = m.groupValues[1].length
                        while (stack.isNotEmpty() && stack.last().first >= indent) stack.removeLast()
                        val depth = minOf((stack.lastOrNull()?.second ?: 0) + 1, LISTS.size)
                        stack.addLast(indent to depth)
                        out += inline(LISTS[depth - 1], m.groupValues[2])
                        i++
                    }
                    continue
                }
                else -> out += inline(Kind.PARAGRAPH, line)
            }
            i++
        }
        return out.ifEmpty { listOf(Line(Kind.PARAGRAPH, "")) }
    }

    private fun inline(kind: Kind, raw: String): Line {
        val (text, spans) = QuickMarkdown.Inline.parse(raw)
        return Line(kind, text, QuickMarkdown.Inline.normalize(text, spans).map { Span(it.type, it.start, it.end) })
    }

    /** Строки → набранное со знаками; [read] даёт обратно те же строки (отрезки — как у [QuickMarkdown.Inline.normalize]). */
    fun write(lines: List<Line>): String {
        val out = mutableListOf<String>()
        var i = 0
        var depth = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.kind !in LISTS) depth = 0
            when (l.kind) {
                Kind.CODE -> {
                    out += "```"
                    while (i < lines.size && lines[i].kind == Kind.CODE) out += lines[i++].text
                    out += "```"
                    continue
                }
                Kind.DIVIDER -> out += "---"
                Kind.HEADING1, Kind.HEADING2, Kind.HEADING3 -> out += "#".repeat(HEADINGS.indexOf(l.kind) + 1) + " " + checked(l)
                Kind.QUOTE -> out += "--> " + checked(l)
                Kind.LIST1, Kind.LIST2, Kind.LIST3 -> {
                    // Пункт глубже соседа сверху больше чем на ступень не бывает: разбор вложит его на ступень.
                    depth = minOf(LISTS.indexOf(l.kind) + 1, depth + 1)
                    out += "  ".repeat(depth - 1) + "- " + checked(l)
                }
                Kind.PARAGRAPH -> out += lineStart(checked(l))
            }
            i++
        }
        return out.joinToString("\n")
    }

    /** Строка абзаца не должна стать заголовком, пунктом, цитатой, разделителем или таблицей. */
    private fun lineStart(s: String): String {
        val lead = s.length - s.trimStart().length
        val t = s.substring(lead)
        val sign = QuickMarkdown.HEADING.matches(t) || t.startsWith("-->") || QuickMarkdown.LIST_ITEM.matches(s) ||
            t.trimEnd() == "---" || t.startsWith("|")
        return if (sign) s.substring(0, lead) + "\\" + t else s
    }

    /**
     * Текст строки со знаками. Сверка обратным разбором: не сошлось (знаки вплотную друг к другу,
     * отрезок по краю адреса) — последний отрезок снимается, текст не теряется никогда.
     */
    private fun checked(l: Line): String {
        var keep = QuickMarkdown.Inline.normalize(l.text, l.spans.map { QuickMarkdown.Span(it.type, it.start, it.end) })
        while (true) {
            val written = QuickMarkdown.Inline.write(l.text, keep)
            val (back, spans) = QuickMarkdown.Inline.parse(written)
            val got = QuickMarkdown.Inline.normalize(back, spans.filter { it.type in QuickMarkdown.Inline.PAIRED }.map { QuickMarkdown.Span(it.type, it.start, it.end) })
            if (back == l.text && got.toSet() == keep.toSet()) return written
            if (keep.isEmpty()) return written
            keep = keep.dropLast(1)
        }
    }
}
