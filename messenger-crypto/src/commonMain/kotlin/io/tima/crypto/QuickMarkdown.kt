package io.tima.crypto

/**
 * Быстрое оформление — упрощённый Markdown (doc_mig/РЕДАКТОР-СООБЩЕНИЙ.md §7, ПЛАН-(РС) РС2).
 *
 * Набранный текст со знаками → узлы и разметка (ADR-0011, ADR-0044). В сообщение уходят они, а не
 * текст Markdown: получатель знаков не разбирает.
 *
 * **Строки.** Заголовок `# `…`### `, список `- ` (вложенность пробелами, три уровня, глубже —
 * прижимается к третьему), цитата `-->`, разделитель `---` / `***`, блок кода между строками ```` ``` ````,
 * таблица GFM с объединением `^^` (с верхней) и `<<` (с левой). Перед знаком в начале строки —
 * любое число пробелов; табуляция — 4 пробела. Остальные строки подряд — один абзац, Enter внутри
 * абзаца — новая строка.
 *
 * **Внутри строки.** `**жирный**`, `*курсив*`, `++подчёркнутый++`, `==маркер==`, `~~зачёркнутый~~`,
 * `` `код` ``. Открывающий знак — перед непробельным, закрывающий — после непробельного; без пары —
 * текст (`C++`, `a == b`). `_` и `__` не значат ничего. Внутри кода и голого адреса разметки нет.
 * `\` перед знаком делает его текстом. `#тег` без пробела — хештег; `@Ник` — упоминание (в блок
 * ответа его превращает клиент: ему известна переписка).
 *
 * **«Знаки md» выключены** — [plain]: текст уходит как есть, знаки остаются знаками.
 */
object QuickMarkdown {

    /** Знаки выключены: текст как есть, одним узлом. */
    fun plain(text: String): MessageContent = MessageContent.text(text)

    /** Разобрать набранный текст. Без единого знака — обычное сообщение без разметки. */
    fun parse(text: String): MessageContent {
        val b = Builder()
        val lines = text.replace("\r\n", "\n").replace("\t", "    ").split('\n')
        var i = 0
        val paragraph = mutableListOf<String>()
        fun flushParagraph() {
            val joined = paragraph.joinToString("\n").trim('\n')
            if (joined.isNotBlank()) b.textBlock(BlockType.PARAGRAPH, joined)
            paragraph.clear()
        }
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.trimEnd() == "```" -> {
                    flushParagraph()
                    val code = mutableListOf<String>()
                    var j = i + 1
                    while (j < lines.size && lines[j].trim() != "```") code += lines[j++]
                    if (j < lines.size) {
                        b.literalBlock(BlockType.CODE_BLOCK, code.joinToString("\n"))
                        i = j + 1
                    } else {
                        // Нет закрывающей строки — это не блок кода, а текст.
                        paragraph += line
                        i++
                    }
                    continue
                }
                isTableStart(lines, i) -> {
                    flushParagraph()
                    var j = i
                    val rows = mutableListOf<String>()
                    while (j < lines.size && lines[j].trimStart().startsWith("|")) rows += lines[j++]
                    b.table(rows)
                    i = j
                    continue
                }
                trimmed.trimEnd() == "---" || trimmed.trimEnd() == "***" -> {
                    flushParagraph()
                    b.divider()
                }
                HEADING.matches(trimmed) -> {
                    flushParagraph()
                    val m = HEADING.find(trimmed)!!
                    b.textBlock(BlockType.HEADING, m.groupValues[2], level = m.groupValues[1].length)
                }
                trimmed.startsWith("-->") -> {
                    flushParagraph()
                    val quote = mutableListOf<String>()
                    var j = i
                    while (j < lines.size && lines[j].trimStart().startsWith("-->")) {
                        quote += lines[j].trimStart().removePrefix("-->").removePrefix(" ")
                        j++
                    }
                    b.quote(quote)
                    i = j
                    continue
                }
                LIST_ITEM.matches(line) -> {
                    flushParagraph()
                    var j = i
                    val items = mutableListOf<Pair<Int, String>>()
                    while (j < lines.size && LIST_ITEM.matches(lines[j])) {
                        val m = LIST_ITEM.find(lines[j])!!
                        items += m.groupValues[1].length to m.groupValues[2]
                        j++
                    }
                    b.list(items)
                    i = j
                    continue
                }
                else -> paragraph += line
            }
            i++
        }
        flushParagraph()
        return b.build()
    }

    private val HEADING = Regex("^(#{1,3}) (.*)$")
    private val LIST_ITEM = Regex("^( *)- (.*)$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")

    private fun isTableStart(lines: List<String>, i: Int): Boolean =
        lines[i].trimStart().startsWith("|") && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1])

    // ── сборка узлов и разметки ────────────────────────────────────────────

    private class Builder {
        val nodes = mutableListOf<String>()
        val blocks = mutableListOf<MarkupBlock>()
        val entities = mutableListOf<MarkupEntity>()
        private var formatted = false

        private fun node(raw: String, parseInline: Boolean = true): Int {
            val id = nodes.size + 1
            if (parseInline) {
                val (text, spans) = Inline.parse(raw)
                nodes += text
                for (s in spans) entities += s.toEntity(id)
                if (spans.isNotEmpty()) formatted = true
            } else {
                nodes += raw
            }
            return id
        }

        fun textBlock(type: BlockType, raw: String, level: Int = 0) {
            if (type != BlockType.PARAGRAPH) formatted = true
            blocks += MarkupBlock(type = type, nodeIds = listOf(node(raw)), level = level)
        }

        fun literalBlock(type: BlockType, raw: String) {
            formatted = true
            blocks += MarkupBlock(type = type, nodeIds = listOf(node(raw, parseInline = false)))
        }

        fun divider() {
            formatted = true
            blocks += MarkupBlock(type = BlockType.DIVIDER)
        }

        fun quote(lines: List<String>) {
            formatted = true
            blocks += MarkupBlock(type = BlockType.QUOTE, nodeIds = lines.map { node(it) })
        }

        /** Пункты с отступом: вложен в ближайший выше с меньшим отступом; глубже третьего — прижат к третьему. */
        fun list(items: List<Pair<Int, String>>) {
            formatted = true
            class Item(val indent: Int, val depth: Int, val nodeId: Int, val parent: Item?) {
                val children = mutableListOf<Item>()
            }
            val roots = mutableListOf<Item>()
            val stack = ArrayDeque<Item>()
            for ((indent, raw) in items) {
                while (stack.isNotEmpty() && stack.last().indent >= indent) stack.removeLast()
                var parent = stack.lastOrNull()
                // Глубже третьего уровня не бывает: такой пункт встаёт рядом с родителем на третьем.
                if (parent != null && parent.depth == MarkupRules.MAX_LIST_DEPTH) parent = parent.parent
                val item = Item(indent, (parent?.depth ?: 0) + 1, node(raw), parent)
                if (parent == null) roots += item else parent.children += item
                stack.addLast(item)
            }
            fun toBlock(list: List<Item>): MarkupBlock = MarkupBlock(
                type = BlockType.LIST,
                children = list.map { item ->
                    MarkupBlock(
                        type = BlockType.LIST_ITEM,
                        nodeIds = listOf(item.nodeId),
                        children = if (item.children.isEmpty()) emptyList() else listOf(toBlock(item.children)),
                    )
                },
            )
            blocks += toBlock(roots)
        }

        fun table(rows: List<String>) {
            formatted = true
            val header = splitRow(rows[0])
            val aligns = splitRow(rows[1]).map { sep ->
                val s = sep.trim()
                when {
                    s.startsWith(":") && s.endsWith(":") -> "center"
                    s.endsWith(":") -> "end"
                    else -> null
                }
            }
            val body = rows.drop(2).map(::splitRow)
            val tableRows = (listOf(header) + body).map { cells ->
                MarkupBlock(
                    type = BlockType.ROW,
                    children = cells.mapIndexed { col, raw ->
                        val t = raw.trim()
                        val style = buildMap {
                            aligns.getOrNull(col)?.let { put("align", it) }
                            if (t == "^^") put("merge", "up")
                            if (t == "<<") put("merge", "left")
                        }
                        val merged = t == "^^" || t == "<<"
                        MarkupBlock(
                            type = BlockType.CELL,
                            nodeIds = if (merged) emptyList() else listOf(node(t.replace("\\^^", "^^").replace("\\<<", "<<"))),
                            style = style,
                        )
                    },
                )
            }
            blocks += MarkupBlock(type = BlockType.TABLE, children = tableRows)
        }

        /** Ячейки строки таблицы: делится по `|`, кроме `\|`; крайние пустые — от рамки. */
        private fun splitRow(line: String): List<String> {
            val cells = mutableListOf<String>()
            val cur = StringBuilder()
            var k = 0
            val s = line.trim()
            while (k < s.length) {
                val c = s[k]
                if (c == '\\' && k + 1 < s.length && s[k + 1] == '|') { cur.append('|'); k += 2; continue }
                if (c == '|') { cells += cur.toString(); cur.clear() } else cur.append(c)
                k++
            }
            cells += cur.toString()
            if (cells.isNotEmpty() && cells.first().isBlank() && s.startsWith("|")) cells.removeAt(0)
            if (cells.isNotEmpty() && cells.last().isBlank() && s.endsWith("|")) cells.removeAt(cells.lastIndex)
            return cells
        }

        fun build(): MessageContent {
            if (!formatted) return MessageContent.text(nodes.joinToString("\n\n"))
            return MessageContent(
                nodes = nodes.toList(),
                markup = Markup(n = Markup.idsFor(nodes.size), entities = entities.toList(), blocks = blocks.toList()),
            )
        }
    }

    // ── внутри строки ──────────────────────────────────────────────────────

    /** Отрезок оформления в тексте узла. */
    internal data class Span(val type: EntityType, val start: Int, val end: Int, val attribute: String = "") {
        fun toEntity(nodeId: Int) = MarkupEntity(type = type, nodeId = nodeId, start = start, length = end - start, attribute = attribute)
    }

    internal object Inline {
        private val PAIRS = listOf("**" to EntityType.BOLD, "++" to EntityType.UNDERLINE, "==" to EntityType.HIGHLIGHT, "~~" to EntityType.STRIKETHROUGH, "*" to EntityType.ITALIC)
        private const val ESCAPABLE = "\\*+=~`#-|_<^>@"
        private val URL = Regex("^(https?://|www\\.)\\S+")
        private val WORD = Regex("^[\\p{L}\\p{N}_]+")
        private val NICK = Regex("^[\\p{L}\\p{N}_.]+")

        private sealed interface Tok
        private class Chars(val text: String) : Tok
        private class Code(val text: String) : Tok
        private class Tag(val type: EntityType, val text: String, val attribute: String) : Tok
        private class Mark(val raw: String, val type: EntityType, val canOpen: Boolean, val canClose: Boolean) : Tok {
            var pairedWith: Mark? = null
            var opener = false
        }

        fun parse(src: String): Pair<String, List<Span>> {
            val toks = tokenize(src)
            pair(toks)
            val out = StringBuilder()
            val spans = mutableListOf<Span>()
            val openAt = HashMap<Mark, Int>()
            for (t in toks) when (t) {
                is Chars -> out.append(t.text)
                is Code -> { val s = out.length; out.append(t.text); spans += Span(EntityType.CODE, s, out.length) }
                is Tag -> { val s = out.length; out.append(t.text); spans += Span(t.type, s, out.length, t.attribute) }
                is Mark -> when {
                    t.pairedWith == null -> out.append(t.raw)
                    t.opener -> openAt[t] = out.length
                    else -> {
                        val start = openAt.getValue(t.pairedWith!!)
                        if (out.length > start) spans += Span(t.type, start, out.length)
                    }
                }
            }
            return out.toString() to spans.sortedWith(compareBy({ it.start }, { -it.end }))
        }

        private fun tokenize(src: String): List<Tok> {
            val toks = mutableListOf<Tok>()
            val buf = StringBuilder()
            fun flush() { if (buf.isNotEmpty()) { toks += Chars(buf.toString()); buf.clear() } }
            var i = 0
            while (i < src.length) {
                val c = src[i]
                val wordStart = i == 0 || src[i - 1].isWhitespace() || src[i - 1] in "([{\"'«"
                // Экранирование: знак после `\` — текст.
                if (c == '\\' && i + 1 < src.length && src[i + 1] in ESCAPABLE) { buf.append(src[i + 1]); i += 2; continue }
                // Голый адрес: внутри разметки нет.
                if (wordStart) {
                    val url = URL.find(src.substring(i))
                    if (url != null) { buf.append(url.value); i += url.value.length; continue }
                }
                // Код: до парного обратного апострофа, внутри разметки нет.
                if (c == '`') {
                    val close = src.indexOf('`', i + 1)
                    if (close > i + 1) { flush(); toks += Code(src.substring(i + 1, close)); i = close + 1; continue }
                }
                if (wordStart && c == '#') {
                    val tag = WORD.find(src.substring(i + 1))
                    if (tag != null) {
                        flush(); toks += Tag(EntityType.HASHTAG, "#" + tag.value, tag.value.lowercase()); i += 1 + tag.value.length
                        continue
                    }
                }
                if (wordStart && c == '@') {
                    val nick = NICK.find(src.substring(i + 1))?.value?.trimEnd('.')
                    if (!nick.isNullOrEmpty()) {
                        flush(); toks += Tag(EntityType.MENTION, "@$nick", nick); i += 1 + nick.length
                        continue
                    }
                }
                val pair = PAIRS.firstOrNull { (mark, _) -> src.startsWith(mark, i) }
                if (pair != null) {
                    val (mark, type) = pair
                    val before = src.getOrNull(i - 1)
                    val after = src.getOrNull(i + mark.length)
                    val canOpen = after != null && !after.isWhitespace()
                    val canClose = before != null && !before.isWhitespace()
                    if (canOpen || canClose) {
                        flush(); toks += Mark(mark, type, canOpen, canClose); i += mark.length; continue
                    }
                    buf.append(mark); i += mark.length; continue
                }
                buf.append(c); i++
            }
            flush()
            return toks
        }

        /** Пары знаков: закрывающий ищет ближайший открывающий того же вида; между ними открытые сгорают. */
        private fun pair(toks: List<Tok>) {
            val stack = mutableListOf<Mark>()
            for (t in toks) {
                if (t !is Mark) continue
                if (t.canClose) {
                    val idx = stack.indexOfLast { it.type == t.type && it.raw == t.raw }
                    if (idx >= 0) {
                        val opener = stack[idx]
                        opener.pairedWith = t; opener.opener = true
                        t.pairedWith = opener
                        while (stack.size > idx) stack.removeAt(stack.lastIndex)
                        continue
                    }
                }
                if (t.canOpen) stack += t
            }
        }
    }
}
