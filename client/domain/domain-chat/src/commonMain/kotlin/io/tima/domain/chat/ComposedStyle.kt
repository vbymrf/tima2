package io.tima.domain.chat

/**
 * Раздел «Стиль» (Решения §10.2): стиль действует на выбранное — кусок сверху вниз (ряд, виджет во
 * всю ширину, текст) или ячейку ряда; виджет и текст в ячейке берут стиль своей ячейки. Значения —
 * имена палитры и ступени (ADR-0044 §4); проверяет их та же строгая `MarkupRules` на отправке.
 */
object ComposedStyle {

    /** Ступени свойств — в порядке показа. */
    val STEPS: Map<String, List<String>> = mapOf(
        "size" to listOf("smaller", "normal", "larger"),
        "align" to listOf("start", "center", "end"),
        "valign" to listOf("top", "center", "bottom"),
        "padding" to listOf("none", "small", "normal", "large"),
        "divider" to listOf("hidden", "1", "2", "4"),
    )

    /** Свойства, значение которых — имя палитры. */
    val PALETTE_KEYS = setOf("background", "color", "divider_color")

    /** Чей стиль меняется: ячейки, если место в ряду; иначе куска. */
    private fun cellOf(doc: ComposedDoc, at: Spot): Int? =
        at.cell.takeIf { doc.items.getOrNull(at.item) is ComposedItem.Row && it >= 0 }

    fun styleAt(doc: ComposedDoc, at: Spot): Map<String, String> {
        val item = doc.items.getOrNull(at.item) ?: return emptyMap()
        val cell = cellOf(doc, at)
        return if (cell != null) (item as ComposedItem.Row).cells.getOrNull(cell)?.style.orEmpty() else item.style
    }

    /** Заменить стиль выбранного целиком (Сброс — пустым, JSON — разобранным). */
    fun setStyle(doc: ComposedDoc, at: Spot, style: Map<String, String>): ComposedDoc {
        val item = doc.items.getOrNull(at.item) ?: return doc
        val clean = style.filterKeys { it != "width" }
        val cell = cellOf(doc, at)
        val next: ComposedItem = if (cell != null) {
            val row = item as ComposedItem.Row
            row.copy(cells = row.cells.mapIndexed { i, c -> if (i == cell) c.copy(style = clean) else c })
        } else {
            when (item) {
                is ComposedItem.Text -> item.copy(style = clean)
                is ComposedItem.Widget -> item.copy(style = clean)
                is ComposedItem.Row -> item.copy(style = clean)
            }
        }
        return doc.copy(items = doc.items.toMutableList().apply { set(at.item, next) })
    }

    /** Одно свойство; `null` — убрать его. */
    fun with(doc: ComposedDoc, at: Spot, key: String, value: String?): ComposedDoc {
        val now = styleAt(doc, at)
        return setStyle(doc, at, if (value == null) now - key else now + (key to value))
    }

    /** Стиль выбранного текстом JSON — «только изменённое» (Решения §10.2). */
    fun toJson(style: Map<String, String>): String =
        if (style.isEmpty()) "{}" else style.entries.joinToString(",\n  ", "{\n  ", "\n}") { (k, v) -> "\"${esc(k)}\": \"${esc(v)}\"" }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Разбор JSON стиля: объект из строк. Не JSON или не строки — `null`; что в нём допустимо,
     * решает проверка разметки, а не разбор.
     */
    fun fromJson(text: String): Map<String, String>? {
        val t = text.trim()
        if (!t.startsWith("{") || !t.endsWith("}")) return null
        val body = t.substring(1, t.length - 1).trim()
        if (body.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var i = 0
        fun skipWs() { while (i < body.length && body[i].isWhitespace()) i++ }
        fun str(): String? {
            skipWs()
            if (i >= body.length || body[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < body.length && body[i] != '"') {
                if (body[i] == '\\' && i + 1 < body.length) { i++ }
                sb.append(body[i]); i++
            }
            if (i >= body.length) return null
            i++
            return sb.toString()
        }
        while (true) {
            val k = str() ?: return null
            skipWs()
            if (i >= body.length || body[i] != ':') return null
            i++
            val v = str() ?: return null
            out[k] = v
            skipWs()
            if (i >= body.length) return out
            if (body[i] != ',') return null
            i++
        }
    }
}

/**
 * Таблица — где угодно, если в её ячейку помещается «99» (Решения §10.3, В26, РС-В10). Проверяется
 * для самого узкого пузыря и самого крупного шрифта сообщений: влезло там — влезет у любого.
 *
 * Числа — по коду, а не на глаз: пузырь не шире 290 dp (`Bubble.kt`, макет) без полей 14 + 14 и
 * рамки — 260 dp содержимого; ячейка таблицы — шрифт 13 sp × (22 / 15) ≈ 19 sp, «99» ≈ 21 dp,
 * поля ячейки 6 + 6 и линия 1 — около 35 dp на столбец. Итого в ряду во всю ширину — 7 столбцов.
 */
object TableFit {
    const val CONTENT_DP = 260
    const val COLUMN_DP = 35

    /** Сколько столбцов помещается в место шириной [percent] процентов пузыря. */
    fun maxColumns(percent: Int = 100): Int = (CONTENT_DP * percent / 100 / COLUMN_DP).coerceAtLeast(1)

    /** Самая широкая таблица в набранном тексте — число столбцов; таблиц нет — 0. */
    fun widest(source: String): Int {
        val lines = source.lines()
        var widest = 0
        for (i in 0 until lines.size - 1) {
            if (lines[i].trimStart().startsWith("|") && SEPARATOR.matches(lines[i + 1])) {
                widest = maxOf(widest, columns(lines[i]))
            }
        }
        return widest
    }

    private fun columns(line: String): Int {
        var n = 0
        var escaped = false
        val s = line.trim().removePrefix("|").removeSuffix("|")
        for (ch in s) {
            if (ch == '|' && !escaped) n++
            escaped = ch == '\\' && !escaped
        }
        return n + 1
    }

    /** Куски-тексты с таблицей шире пузыря. */
    fun tooWide(doc: ComposedDoc): List<Int> = doc.items.mapIndexedNotNull { i, item ->
        i.takeIf { item is ComposedItem.Text && widest(item.source) > maxColumns() }
    }

    private val SEPARATOR = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
}
