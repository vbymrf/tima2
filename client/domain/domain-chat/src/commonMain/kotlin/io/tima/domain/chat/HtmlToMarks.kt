package io.tima.domain.chat

/**
 * «С оформл.» — вставка из системного буфера (Решения §5): из HTML берётся **только текст и
 * простое оформление** — заголовки, жирный, курсив, подчёркнутый, зачёркнутый, маркер, списки,
 * цитаты, код, таблица. Картинок и адресов ссылок нет никаких: от ссылки остаётся текст. Цвет,
 * шрифт, размер, фон отбрасываются.
 *
 * Результат — набранный текст со знаками быстрого оформления (Решения §7): дальше его разбирает тот
 * же разбор, что и набранное руками, и показывает строка редактора со знаками. Знак, который в HTML
 * был просто знаком, экранируется — иначе `2*3*4` из буфера стало бы курсивом.
 */
object HtmlToMarks {

    private val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)\b[^>]*?(/?)>|<!--.*?-->|<!\[CDATA\[.*?]]>|<![^>]*>|<\?[^>]*>""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE = mapOf(
        "b" to "**", "strong" to "**",
        "i" to "*", "em" to "*",
        "u" to "++", "ins" to "++",
        "s" to "~~", "strike" to "~~", "del" to "~~",
        "mark" to "==",
        "code" to "`", "kbd" to "`", "tt" to "`",
    )
    private val BLOCK = setOf("p", "div", "section", "article", "header", "footer", "main", "aside", "nav", "figure", "figcaption", "address", "center", "dd", "dt", "dl", "form", "fieldset")
    private val SKIP = setOf("script", "style", "head", "title", "noscript", "template", "svg", "math", "object", "iframe", "select", "button", "textarea")
    private const val INLINE_SIGNS = "*+=~`\\|"

    /** Набранный текст со знаками из HTML; пустой HTML — пустая строка. */
    fun convert(html: String): String {
        val body = html.substringAfter("<!--StartFragment-->", html).substringBefore("<!--EndFragment-->")
        val c = Converter()
        var at = 0
        for (m in TAG.findAll(body)) {
            if (m.range.first > at) c.text(body.substring(at, m.range.first))
            at = m.range.last + 1
            val name = m.groupValues[2].lowercase()
            if (name.isEmpty()) continue
            val closing = m.groupValues[1] == "/"
            val selfClosing = m.groupValues[3] == "/"
            if (closing) c.close(name) else { c.open(name); if (selfClosing) c.close(name) }
        }
        if (at < body.length) c.text(body.substring(at))
        return c.result()
    }

    /** Знаки HTML-сущностей — те, что встречаются в буфере браузера и офиса. */
    fun decode(raw: String): String {
        if ('&' !in raw) return raw
        return Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);").replace(raw) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let(::codePoint) ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.let(::codePoint) ?: m.value
                else -> ENTITIES[e] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int): String = when {
        cp in 0..0xFFFF -> cp.toChar().toString()
        cp <= 0x10FFFF -> {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
        else -> ""
    }

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "laquo" to "«", "raquo" to "»", "mdash" to "—", "ndash" to "–", "hellip" to "…", "copy" to "©",
        "reg" to "®", "trade" to "™", "deg" to "°", "times" to "×", "euro" to "€", "bull" to "•",
        "rarr" to "→", "larr" to "←", "middot" to "·", "shy" to "",
    )

    /** Знак разметки, который в HTML был текстом, — с `\`; в начале строки — и строковые знаки. */
    private fun escape(text: String, lineStart: Boolean): String {
        val out = StringBuilder()
        var start = lineStart
        for (ch in text) {
            if (ch in INLINE_SIGNS || (start && (ch == '#' || ch == '-' || ch == '>'))) out.append('\\')
            out.append(ch)
            if (!ch.isWhitespace()) start = false
        }
        return out.toString()
    }

    private class Frame(val sign: String) {
        val text = StringBuilder()
    }

    private class Converter {
        private val out = StringBuilder()
        private val inline = ArrayDeque<Frame>()
        private var skip = 0
        private var pre = 0
        private var quote = 0
        private val lists = ArrayDeque<String>()
        private var cell: StringBuilder? = null
        private var row: MutableList<String>? = null
        private val rows = mutableListOf<List<String>>()

        /** Куда пишется текст: ячейка таблицы, рамка оформления или документ. */
        private fun sink(): StringBuilder = inline.lastOrNull()?.text ?: cell ?: out

        private fun atLineStart(): Boolean {
            val s = sink()
            if (s !== out) return s.isEmpty()
            val tail = out.substring(out.lastIndexOf("\n") + 1)
            return tail.isBlank() || tail == prefix()
        }

        /** Начало строки внутри цитаты — `--> `. */
        private fun prefix(): String = if (quote > 0) "--> " else ""

        private fun newline() {
            if (cell != null) { cell!!.append(' '); return }
            flushInline()
            if (out.isEmpty() || out.endsWith("\n")) return
            out.append('\n')
        }

        /** Новая строка между блоками: абзац, заголовок, список начинаются с начала строки. */
        private fun blockBreak() {
            if (cell != null) { cell!!.append(' '); return }
            flushInline()
            if (out.isEmpty()) return
            if (!out.endsWith("\n")) out.append('\n')
        }

        private fun lineStart() {
            if (cell != null) return
            if (out.isEmpty() || out.endsWith("\n")) out.append(prefix())
        }

        /** Незакрытое оформление при смене блока — закрыть там, где кончилась строка. */
        private fun flushInline() {
            while (inline.isNotEmpty()) close(inline.last().signName())
        }

        private fun Frame.signName(): String = INLINE.entries.first { it.value == sign }.key

        fun text(raw: String) {
            if (skip > 0) return
            val decoded = decode(raw)
            if (pre > 0) {
                out.append(decoded)
                return
            }
            val collapsed = decoded.replace(Regex("[ \\t\\r\\n\\f]+"), " ")
            if (collapsed.isEmpty()) return
            val s = sink()
            // Пробел в начале строки ничего не значит, а в начале блока сбивает знаки.
            val trimmed = if (s === out && atLineStart()) collapsed.trimStart() else collapsed
            if (trimmed.isEmpty()) return
            if (s === out) lineStart()
            s.append(escape(trimmed, s === out && atLineStart()))
        }

        fun open(name: String) {
            if (name in SKIP) { skip++; return }
            if (skip > 0) return
            when {
                name in INLINE && pre == 0 -> inline.addLast(Frame(INLINE.getValue(name)))
                name == "br" -> if (pre > 0) out.append('\n') else newline()
                name == "hr" -> { blockBreak(); out.append("---\n") }
                name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> {
                    blockBreak()
                    lineStart()
                    out.append("#".repeat((name[1] - '0').coerceAtMost(3))).append(' ')
                }
                name == "pre" -> { blockBreak(); out.append("```\n"); pre++ }
                name == "blockquote" -> { blockBreak(); quote++ }
                name == "ul" || name == "ol" -> { if (lists.isEmpty()) blockBreak() else newline(); lists.addLast(name) }
                name == "li" -> {
                    newline()
                    lineStart()
                    out.append("    ".repeat((lists.size - 1).coerceIn(0, 2))).append("- ")
                }
                name == "table" -> { blockBreak(); rows.clear() }
                name == "tr" -> row = mutableListOf()
                name == "td" || name == "th" -> cell = StringBuilder()
                name in BLOCK -> blockBreak()
            }
        }

        fun close(name: String) {
            if (name in SKIP) { skip = (skip - 1).coerceAtLeast(0); return }
            if (skip > 0) return
            when {
                name in INLINE && pre == 0 -> {
                    val idx = inline.indexOfLast { it.sign == INLINE.getValue(name) }
                    if (idx < 0) return
                    // Закрыть и всё, что открыто внутри: HTML из буфера не всегда вложен честно.
                    while (inline.size > idx) {
                        val f = inline.removeLast()
                        val content = f.text.toString()
                        val parent = sink()
                        if (content.isBlank()) { parent.append(content); continue }
                        val lead = content.takeWhile { it == ' ' }
                        val trail = content.takeLastWhile { it == ' ' }
                        if (parent === out) lineStart()
                        parent.append(lead).append(f.sign).append(content.trim(' ')).append(f.sign).append(trail)
                    }
                }
                name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> newline()
                name == "pre" -> {
                    pre = (pre - 1).coerceAtLeast(0)
                    if (!out.endsWith("\n")) out.append('\n')
                    out.append("```\n")
                }
                name == "blockquote" -> { newline(); quote = (quote - 1).coerceAtLeast(0) }
                name == "ul" || name == "ol" -> { lists.removeLastOrNull(); newline() }
                name == "li" -> newline()
                name == "td" || name == "th" -> {
                    flushInlineInto(cell)
                    // `|` в тексте ячейки уже экранирован при записи текста.
                    row?.add(cell?.toString()?.trim().orEmpty())
                    cell = null
                }
                name == "tr" -> { row?.let { if (it.isNotEmpty()) rows += it }; row = null }
                name == "table" -> {
                    if (rows.isNotEmpty()) {
                        val width = rows.maxOf { it.size }
                        val padded = rows.map { r -> r + List(width - r.size) { "" } }
                        out.append("| ").append(padded[0].joinToString(" | ")).append(" |\n")
                        out.append("|").append(List(width) { "---" }.joinToString("|")).append("|\n")
                        for (r in padded.drop(1)) out.append("| ").append(r.joinToString(" | ")).append(" |\n")
                    }
                    rows.clear()
                }
                name in BLOCK -> newline()
            }
        }

        private fun flushInlineInto(target: StringBuilder?) {
            if (target == null) return
            while (inline.isNotEmpty()) close(inline.last().signName())
        }

        fun result(): String {
            flushInline()
            return out.toString()
                .replace(Regex("[ \\u00A0]+\n"), "\n")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim('\n', ' ')
        }
    }
}
