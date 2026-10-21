package io.tima.domain.chat

/**
 * Место в документе редактора: кусок сверху вниз, ячейка ряда, виджет в ячейке. [cell] `-1` —
 * кусок целиком; [widget] `-1` — ячейка целиком, [TEXT] — текст ячейки (Решения §10.3: написанное в
 * ячейке становится виджетом «Текст» с ручкой). Ячейка ряда — по номеру слева направо, строка за
 * строкой: строка `cell / columns`, столбец `cell % columns`.
 */
data class Spot(val item: Int, val cell: Int = -1, val widget: Int = -1) {
    companion object {
        const val TEXT = -2
        /** Заголовок пункта списка (ADR-0049): `Spot(кусок, пункт, TITLE)`; `TEXT` — его содержимое. */
        const val TITLE = -3
    }
}

/** Куда опустили перенесённое: в ячейку (в конец) или между кусками сверху вниз. */
sealed interface Drop {
    data class IntoCell(val item: Int, val cell: Int) : Drop
    /** Перед куском [before]; `items.size` — в самый низ. */
    data class Between(val before: Int) : Drop

    /**
     * В готовый список [item] новым пунктом перед пунктом [before] (`items.size` — в конец): подборка
     * и подкладка идут по списку (заказчик 2026-10-21).
     */
    data class IntoList(val item: Int, val before: Int) : Drop
}

/** Перенесённая картинка — снова во всю ширину нового места, дальше её можно сузить (заказчик 2026-10-21). */
internal fun ComposedWidget.fresh(): ComposedWidget = if (this is ComposedWidget.Image) copy(share = 100) else this

/** Подборка и подкладка — их можно вести по готовому списку; подкладку — не в ряд. */
val ComposedWidget.isRef: Boolean get() = this is ComposedWidget.Quoted || this is ComposedWidget.Underlay

/**
 * Встаёт ли виджет в ячейку ряда. Подкладка — нет (заказчик 2026-10-21), ответ — тоже: он блок верхнего
 * уровня. «Выше» и «Ниже» перепрыгивают ряд с такими целиком.
 */
val ComposedWidget.fitsCell: Boolean get() = this !is ComposedWidget.Underlay && this !is ComposedWidget.Reply

/**
 * Раздел «Разметка» (Решения §10.2–§10.3): ряд строк × столбцов, столбцы и строки, слияние с левой и
 * с верхней, ширина рамкой, выше и ниже, удаление, перенос. Чистые функции над документом — их
 * проверяют тесты, а экран только зовёт.
 *
 * Ширины столбцов в сумме 100 (ADR-0044 §5): ряд создаётся с равными столбцами, добавили или убрали
 * столбец — снова поровну; рамка перераспределяет ширину только между двумя соседними (заказчик
 * 2026-10-18). Слитое всегда прямоугольник: все операции пересобирают ряд из «владельцев» мест.
 */
object ComposedLayout {

    const val MAX_COLUMNS = 7
    const val MAX_LINES = 20

    /** Шаг рамки, проценты. */
    const val STEP = 5

    /** Поровну, остаток — первым: 3 ячейки — 34, 33, 33. */
    fun widths(n: Int): List<Int> = List(n) { i -> 100 / n + if (i < 100 % n) 1 else 0 }

    /** Наименьшая доля столбца по их числу: 2 — 20 %, 3 и 4 — 15 %, больше — 14 %. */
    fun minWidth(columns: Int): Int = when {
        columns <= 1 -> 100
        columns == 2 -> 20
        columns <= 4 -> 15
        else -> 14
    }

    private fun ComposedDoc.withItems(items: List<ComposedItem>) = copy(items = items.ifEmpty { listOf(ComposedItem.Text("")) })

    private fun ComposedDoc.row(at: Int): ComposedItem.Row? = items.getOrNull(at) as? ComposedItem.Row

    private fun ComposedDoc.setRow(at: Int, row: ComposedItem.Row) = copy(items = items.toMutableList().apply { set(at, row) })

    private fun ComposedDoc.setItem(at: Int, item: ComposedItem) = copy(items = items.toMutableList().apply { set(at, item) })

    // ── Сетка «владельцев»: место → номер ячейки, которой оно принадлежит ─────────

    /** Содержимое ячейки без места: текст, виджеты, стиль. */
    private data class Content(val text: String = "", val widgets: List<ComposedWidget> = emptyList(), val style: Map<String, String> = emptyMap()) {
        operator fun plus(o: Content) = Content(
            listOf(text, o.text).filter { it.isNotBlank() }.joinToString("\n"),
            widgets + o.widgets,
            style,
        )
    }

    private class Grid(var lines: Int, var columns: Int, val owner: MutableList<Int>, val content: MutableMap<Int, Content>, var widths: MutableList<Int>) {
        var next = (owner.maxOrNull() ?: -1) + 1
        fun at(l: Int, c: Int) = owner[l * columns + c]
        fun fresh(): Int = next++.also { content[it] = Content() }
    }

    /**
     * Владелец места: слитая с левой — владелец левой, с верхней — верхней. Слияние в первой строке
     * «с верхней» (или в первом столбце «с левой») ничего не значит — место само себе владелец.
     */
    private fun gridOf(row: ComposedItem.Row): Grid {
        val cols = row.columns.coerceAtLeast(1)
        val lines = row.lines
        val owner = MutableList(lines * cols) { it }
        val content = mutableMapOf<Int, Content>()
        for (p in 0 until lines * cols) {
            val cell = row.cells.getOrNull(p) ?: ComposedCell(0)
            val l = p / cols
            val c = p % cols
            owner[p] = when {
                cell.merge == "left" && c > 0 -> owner[p - 1]
                cell.merge == "up" && l > 0 -> owner[p - cols]
                else -> p
            }
            if (owner[p] == p) content[p] = Content(cell.text, cell.widgets, cell.style)
        }
        val widths = MutableList(cols) { c -> row.cells.getOrNull(c)?.width ?: 0 }
        if (widths.sum() != 100 || widths.any { it < 1 }) widths.apply { clear(); addAll(widths(cols)) }
        return Grid(lines, cols, owner, content, widths)
    }

    /** Ряд из сетки: первое место владельца — ячейка с содержимым, остальные — слитые с левой или верхней. */
    private fun rowOf(g: Grid, style: Map<String, String>): ComposedItem.Row {
        val seen = mutableSetOf<Int>()
        val cells = (0 until g.lines * g.columns).map { p ->
            val o = g.owner[p]
            val c = p % g.columns
            val width = g.widths[c]
            if (seen.add(o)) {
                val k = g.content[o] ?: Content()
                ComposedCell(width, k.text, k.widgets, k.style)
            } else {
                ComposedCell(width, merge = if (c > 0 && g.owner[p - 1] == o) "left" else "up")
            }
        }
        return ComposedItem.Row(cells, style, g.columns)
    }

    /** Места владельца — для прямоугольника слитого. */
    private fun Grid.places(o: Int): List<Int> = owner.indices.filter { owner[it] == o }

    // ── Ряд, столбцы, строки ─────────────────────────────────────────────────

    /**
     * «Ряд»: после выбранного — ряд [lines] × [columns] с равными столбцами. Выбран виджет во всю
     * ширину — он сам становится первой ячейкой ряда: так собирают «картинка слева, текст справа».
     */
    fun addRow(doc: ComposedDoc, at: Spot, lines: Int = 1, columns: Int = 2): Pair<ComposedDoc, Spot> {
        val item = doc.items.getOrNull(at.item)
        if (item is ComposedItem.Widget) {
            val n = columns.coerceIn(1, MAX_COLUMNS)
            val cells = cellsOf(lines, columns)
            cells[0] = cells[0].copy(widgets = listOf(item.widget))
            return doc.setRow(at.item, ComposedItem.Row(cells, item.style, n)) to Spot(at.item, if (n > 1) 1 else 0)
        }
        return rowAt(doc, at.item + 1, lines, columns)
    }

    /** Пустой ряд [lines] × [columns] на место [index] — туда, где курсор разрезал текст (заказчик 2026-10-21). */
    fun rowAt(doc: ComposedDoc, index: Int, lines: Int = 1, columns: Int = 2): Pair<ComposedDoc, Spot> {
        val at = index.coerceIn(0, doc.items.size)
        val items = doc.items.toMutableList().apply { add(at, ComposedItem.Row(cellsOf(lines, columns), columns = columns.coerceIn(1, MAX_COLUMNS))) }
        return doc.withItems(items) to Spot(at, 0)
    }

    private fun cellsOf(lines: Int, columns: Int): MutableList<ComposedCell> {
        val n = columns.coerceIn(1, MAX_COLUMNS)
        val w = widths(n)
        return MutableList(lines.coerceIn(1, MAX_LINES) * n) { p -> ComposedCell(w[p % n]) }
    }

    /** Строка и столбец выбранной ячейки; ячейка не выбрана — последние. */
    private fun place(row: ComposedItem.Row, at: Spot): Pair<Int, Int> =
        if (at.cell in row.cells.indices) (at.cell / row.columns) to (at.cell % row.columns) else (row.lines - 1) to (row.columns - 1)

    /** «+Столбец»: справа от выбранного, не больше семи; столбцы снова поровну. Внутри слитого — расширяет его. */
    fun addColumn(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        if (row.columns >= MAX_COLUMNS) return doc to at
        val (line, col) = place(row, at)
        val g = gridOf(row)
        val ins = col + 1
        val owner = mutableListOf<Int>()
        for (l in 0 until g.lines) {
            for (c in 0 until g.columns) {
                if (c == ins) owner += newOwner(g, l, c - 1, c)
                owner += g.at(l, c)
            }
            if (ins == g.columns) owner += newOwner(g, l, g.columns - 1, -1)
        }
        val next = Grid(g.lines, g.columns + 1, owner, g.content, widths(g.columns + 1).toMutableList()).also { it.next = g.next }
        return doc.setRow(at.item, rowOf(next, row.style)) to Spot(at.item, line * next.columns + ins)
    }

    /** Новое место между [left] и [right] в строке [l]: внутри слитого — его владелец, иначе своё. */
    private fun newOwner(g: Grid, l: Int, left: Int, right: Int): Int {
        if (left >= 0 && right in 0 until g.columns && g.at(l, left) == g.at(l, right)) return g.at(l, left)
        return g.fresh()
    }

    /** «−Столбец»: выбранный (или последний); последний столбец — ряд убирается целиком. */
    fun removeColumn(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        if (row.columns <= 1) return removeAt(doc, Spot(at.item))
        val (line, col) = place(row, at)
        val g = gridOf(row)
        // Содержимое владельца, у которого не останется мест, теряется вместе со столбцом.
        val owner = mutableListOf<Int>()
        for (l in 0 until g.lines) for (c in 0 until g.columns) if (c != col) owner += g.at(l, c)
        val next = Grid(g.lines, g.columns - 1, owner, g.content, widths(g.columns - 1).toMutableList()).also { it.next = g.next }
        return doc.setRow(at.item, rowOf(next, row.style)) to Spot(at.item, line * next.columns + (col - 1).coerceAtLeast(0))
    }

    /** «+Строка»: под выбранной, не больше двадцати. Внутри слитого по вертикали — расширяет его. */
    fun addLine(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        if (row.lines >= MAX_LINES) return doc to at
        val (line, col) = place(row, at)
        val g = gridOf(row)
        val ins = line + 1
        val owner = mutableListOf<Int>()
        for (l in 0..g.lines) {
            when {
                l < ins -> for (c in 0 until g.columns) owner += g.at(l, c)
                l == ins -> for (c in 0 until g.columns) {
                    owner += if (ins < g.lines && g.at(ins - 1, c) == g.at(ins, c)) g.at(ins, c) else g.fresh()
                }
                else -> for (c in 0 until g.columns) owner += g.at(l - 1, c)
            }
        }
        val next = Grid(g.lines + 1, g.columns, owner, g.content, g.widths).also { it.next = g.next }
        return doc.setRow(at.item, rowOf(next, row.style)) to Spot(at.item, ins * g.columns + col)
    }

    /** «−Строка»: выбранная (или последняя); последняя строка — ряд убирается целиком. */
    fun removeLine(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        if (row.lines <= 1) return removeAt(doc, Spot(at.item))
        val (line, col) = place(row, at)
        val g = gridOf(row)
        val owner = mutableListOf<Int>()
        for (l in 0 until g.lines) if (l != line) for (c in 0 until g.columns) owner += g.at(l, c)
        val next = Grid(g.lines - 1, g.columns, owner, g.content, g.widths).also { it.next = g.next }
        return doc.setRow(at.item, rowOf(next, row.style)) to Spot(at.item, (line - 1).coerceAtLeast(0) * g.columns + col)
    }

    // ── Слияние ──────────────────────────────────────────────────────────────

    /**
     * «С левой» (`left`) и «С верхней» (`up`): выбранная ячейка сливается с соседней, её содержимое
     * переходит к той. Слитое — прямоугольник: что попало в него целиком, тоже сливается; задело
     * другое слитое лишь частью — нельзя (`null`).
     */
    fun join(doc: ComposedDoc, at: Spot, direction: String): Pair<ComposedDoc, Spot>? {
        val row = doc.row(at.item) ?: return null
        if (at.cell !in row.cells.indices) return null
        val g = gridOf(row)
        val l = at.cell / g.columns
        val c = at.cell % g.columns
        val neighbour = when (direction) {
            "left" -> if (c > 0) at.cell - 1 else return null
            "up" -> if (l > 0) at.cell - g.columns else return null
            else -> return null
        }
        val mine = g.owner[at.cell]
        val target = g.owner[neighbour]
        if (mine == target) return null
        val box = g.places(mine) + g.places(target)
        val top = box.minOf { it / g.columns }
        val bottom = box.maxOf { it / g.columns }
        val left = box.minOf { it % g.columns }
        val right = box.maxOf { it % g.columns }
        val inside = (top..bottom).flatMap { r -> (left..right).map { k -> r * g.columns + k } }.toSet()
        val owners = inside.map { g.owner[it] }.toSet()
        // Задели другое слитое только частью — прямоугольника не выйдет.
        if (owners.any { o -> g.places(o).any { it !in inside } }) return null
        val first = inside.min()
        val keeper = g.owner[first]
        var merged = Content(style = g.content[keeper]?.style.orEmpty())
        for (o in owners.sortedBy { o -> g.places(o).min() }) merged += g.content[o] ?: Content()
        for (p in inside) g.owner[p] = keeper
        g.content[keeper] = merged
        return doc.setRow(at.item, rowOf(g, row.style)) to Spot(at.item, first)
    }

    /** «Разъедин.»: слитое снова отдельными ячейками; содержимое остаётся в первой. */
    fun split(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        if (at.cell !in row.cells.indices) return doc to at
        val g = gridOf(row)
        val o = g.owner[at.cell]
        val places = g.places(o)
        if (places.size < 2) return doc to at
        for (p in places.drop(1)) g.owner[p] = g.fresh()
        return doc.setRow(at.item, rowOf(g, row.style)) to Spot(at.item, places.first())
    }

    // ── Ширина рамкой ────────────────────────────────────────────────────────

    /**
     * Рамка между столбцами [boundary] − 1 и [boundary] сдвинута на [delta] процентов: ширина
     * перераспределяется только между ними (заказчик 2026-10-18), не уже наименьшей доли по числу
     * столбцов и не уже, чем нужно содержимому ([RowFit]).
     */
    fun resize(doc: ComposedDoc, item: Int, boundary: Int, delta: Int): ComposedDoc {
        val row = doc.row(item) ?: return doc
        if (boundary !in 1 until row.columns || delta == 0) return doc
        val g = gridOf(row)
        val a = boundary - 1
        val b = boundary
        val pair = g.widths[a] + g.widths[b]
        val minA = maxOf(minWidth(g.columns), RowFit.columnNeed(row, a))
        val minB = maxOf(minWidth(g.columns), RowFit.columnNeed(row, b))
        if (minA + minB > pair) return doc
        val left = (g.widths[a] + delta).coerceIn(minA, pair - minB)
        if (left == g.widths[a]) return doc
        g.widths[a] = left
        g.widths[b] = pair - left
        return doc.setRow(item, rowOf(g, row.style))
    }

    // ── Выше, ниже, удалить, перенос ─────────────────────────────────────────

    /**
     * «Выше» и «Ниже» (−1, +1): виджет внутри ячейки — среди соседей по ячейке; кусок — среди
     * кусков. Ячейки ряда местами не меняются: у них строки и столбцы.
     */
    fun shift(doc: ComposedDoc, at: Spot, by: Int, refTitle: String = "", source: TypedSource = PlainTyped): Pair<ComposedDoc, Spot> {
        // Список (ADR-0049): виджет — внутри пункта, пункт — внутри списка, со своей ступенью.
        val list = doc.items.getOrNull(at.item) as? ComposedItem.ItemList
        if (list != null && at.cell in list.items.indices) {
            val item = list.items[at.cell]
            // Подборка или подкладка своим пунктом (заказчик 2026-10-21): идёт по списку пунктом, на
            // краю — выходит из списка куском во всю ширину.
            val own = at.widget >= 0 && item.widgets.size == 1 && item.widgets[0].isRef && item.text.isBlank()
            if (own) {
                val to = at.cell + by
                if (to in list.items.indices) {
                    val items = list.items.toMutableList().apply { add(to, removeAt(at.cell)) }
                    return doc.setItem(at.item, list.copy(items = ComposedLists.normalized(items, ""))) to at.copy(cell = to, widget = 0)
                }
                val rest = list.items.filterIndexed { i, _ -> i != at.cell }
                val piece = ComposedItem.Widget(item.widgets[0])
                val items = doc.items.toMutableList()
                if (rest.isEmpty()) {
                    items[at.item] = piece
                    return doc.copy(items = items) to Spot(at.item)
                }
                items[at.item] = list.copy(items = ComposedLists.normalized(rest, ""))
                val place = if (by < 0) at.item else at.item + 1
                items.add(place, piece)
                return doc.copy(items = items) to Spot(place)
            }
            if (at.widget >= 0) {
                val to = at.widget + by
                if (to !in item.widgets.indices) return doc to at
                val widgets = item.widgets.toMutableList().apply { add(to, removeAt(at.widget)) }
                return doc.setItem(at.item, list.copy(items = list.items.toMutableList().apply { set(at.cell, item.copy(widgets = widgets)) })) to at.copy(widget = to)
            }
            val to = at.cell + by
            if (to !in list.items.indices) return doc to at
            val items = list.items.toMutableList().apply { add(to, removeAt(at.cell)) }
            return doc.setItem(at.item, list.copy(items = ComposedLists.normalized(items, ""))) to at.copy(cell = to)
        }
        val row = doc.row(at.item)
        if (row != null && at.cell in row.cells.indices && at.widget >= 0) {
            val cell = row.cells[at.cell]
            val to = at.widget + by
            if (to in cell.widgets.indices) {
                val widgets = cell.widgets.toMutableList().apply { add(to, removeAt(at.widget)) }
                val cells = row.cells.toMutableList().apply { set(at.cell, cell.copy(widgets = widgets)) }
                return doc.setRow(at.item, row.copy(cells = cells)) to at.copy(widget = to)
            }
            // На краю ячейки — в соседнюю по порядку чтения (заказчик 2026-10-21): вниз — строка за
            // строкой, ячейка за ячейкой, в начало следующей; вверх — обратно, в конец предыдущей.
            // Слитые места пропускаются. На краю ряда — вон из ряда, куском во всю ширину.
            val widget = cell.widgets[at.widget]
            val owners = row.cells.indices.filter { ownerOf(row, it) == it }
            val next = owners.getOrNull(owners.indexOf(at.cell) + by)
            val emptied = row.cells.toMutableList().apply { set(at.cell, cell.copy(widgets = cell.widgets.filterIndexed { i, _ -> i != at.widget })) }
            if (next != null) {
                val target = emptied[next]
                val widgets = if (by > 0) listOf(widget.fresh()) + target.widgets else target.widgets + widget.fresh()
                emptied[next] = target.copy(widgets = widgets)
                return doc.setRow(at.item, row.copy(cells = emptied)) to Spot(at.item, next, if (by > 0) 0 else widgets.lastIndex)
            }
            val items = doc.items.toMutableList()
            items[at.item] = row.copy(cells = emptied)
            val place = if (by < 0) at.item else at.item + 1
            items.add(place, ComposedItem.Widget(widget.fresh()))
            return doc.copy(items = items) to Spot(place)
        }
        // Текст ячейки — целиком в соседнюю ячейку (вливается в её текст), на краю ряда — вон куском
        // (заказчик 2026-10-21: «тот же механизм» для текста, заголовка, цитаты, списка).
        if (row != null && at.cell in row.cells.indices && at.widget == Spot.TEXT && row.cells[at.cell].text.isNotBlank()) {
            val text = row.cells[at.cell].text
            val owners = row.cells.indices.filter { ownerOf(row, it) == it }
            val next = owners.getOrNull(owners.indexOf(at.cell) + by)
            val cells = row.cells.toMutableList().apply { set(at.cell, row.cells[at.cell].copy(text = "")) }
            if (next != null) {
                val target = cells[next]
                cells[next] = target.copy(text = if (by > 0) merged(source, text, target.text) else merged(source, target.text, text))
                return doc.setRow(at.item, row.copy(cells = cells)) to Spot(at.item, next, Spot.TEXT)
            }
            val items = doc.items.toMutableList()
            items[at.item] = row.copy(cells = cells)
            val place = if (by < 0) at.item else at.item + 1
            items.add(place, ComposedItem.Text(text))
            return doc.copy(items = items) to Spot(place)
        }
        if (row != null && at.cell in row.cells.indices) return doc to at
        val to = at.item + by
        if (at.item !in doc.items.indices || to !in doc.items.indices) return doc to at
        val piece = doc.items[at.item]
        // Виджет рядом с рядом — входит в него (заказчик 2026-10-21): сверху — в первую ячейку первой
        // строки, снизу — в последнюю ячейку последней. Подкладка и ответ ряд перепрыгивают.
        val nextRow = doc.items[to] as? ComposedItem.Row
        if (piece is ComposedItem.Widget && piece.widget.fitsCell && nextRow != null && nextRow.cells.isNotEmpty()) {
            val owners = nextRow.cells.indices.filter { ownerOf(nextRow, it) == it }
            val enter = if (by > 0) owners.first() else owners.last()
            val target = nextRow.cells[enter]
            val widgets = if (by > 0) listOf(piece.widget.fresh()) + target.widgets else target.widgets + piece.widget.fresh()
            val cells = nextRow.cells.toMutableList().apply { set(enter, target.copy(widgets = widgets)) }
            val without = doc.items.toMutableList().apply { removeAt(at.item) }
            val rowAt = if (by < 0) to else to - 1
            without[rowAt] = nextRow.copy(cells = cells)
            return doc.copy(items = without) to Spot(rowAt, enter, if (by > 0) 0 else widgets.lastIndex)
        }
        // Текст и список без виджетов рядом с рядом — входят в него текстом (заказчик 2026-10-21): сверху —
        // в начало первой ячейки, снизу — в конец последней. Список с виджетами ряд перепрыгивает.
        val asText = when (piece) {
            is ComposedItem.Text -> piece.source.takeIf { it.isNotBlank() }
            is ComposedItem.ItemList -> listAsText(piece, source)
            else -> null
        }
        if (asText != null && nextRow != null && nextRow.cells.isNotEmpty()) {
            val owners = nextRow.cells.indices.filter { ownerOf(nextRow, it) == it }
            val enter = if (by > 0) owners.first() else owners.last()
            val target = nextRow.cells[enter]
            val text = if (by > 0) merged(source, asText, target.text) else merged(source, target.text, asText)
            val cells = nextRow.cells.toMutableList().apply { set(enter, target.copy(text = text)) }
            val without = doc.items.toMutableList().apply { removeAt(at.item) }
            val rowAt = if (by < 0) to else to - 1
            without[rowAt] = nextRow.copy(cells = cells)
            return doc.copy(items = without) to Spot(rowAt, enter, Spot.TEXT)
        }
        // Подборка или подкладка рядом с готовым списком — входит в него крайним пунктом (2026-10-21).
        val next = doc.items[to] as? ComposedItem.ItemList
        if (piece is ComposedItem.Widget && piece.widget.isRef && next != null) {
            val entry = ComposedListItem(1, refTitle, widgets = listOf(piece.widget))
            val items = if (by < 0) next.items + entry else listOf(entry) + next.items
            val without = doc.items.toMutableList().apply { removeAt(at.item) }
            val listAt = if (by < 0) to else to - 1
            without[listAt] = next.copy(items = ComposedLists.normalized(items, ""))
            return doc.copy(items = without) to Spot(listAt, if (by < 0) items.lastIndex else 0, 0)
        }
        return doc.copy(items = doc.items.toMutableList().apply { add(to, removeAt(at.item)) }) to Spot(to)
    }

    /** Перенос строк: документ, где теперь текст со строками, и номер первой перенесённой строки в нём. */
    data class Lines(val doc: ComposedDoc, val spot: Spot, val line: Int)

    /**
     * «Выше» и «Ниже» над текстом (заказчик 2026-10-21: «функционально нужно переносить строки только»):
     * строки [a]…[b] текста в [at] (кусок или текст ячейки). Внутри текста — меняются местами с соседней
     * строкой. На краю — уходят за значимый элемент: за виджет, список, метку — в текст по ту сторону, а
     * нет его — новым куском; в ряд — в текст ячейки и дальше по ячейкам; из ряда — так же наружу.
     * Соседний текст без значимого между — просто принимает строки: куски зря не плодятся. Опустевший
     * кусок уходит. `null` — переносить некуда.
     */
    fun moveLines(doc: ComposedDoc, at: Spot, a: Int, b: Int, by: Int, source: TypedSource): Lines? {
        val item = doc.items.getOrNull(at.item) ?: return null
        val row = item as? ComposedItem.Row
        val text = when {
            item is ComposedItem.Text && at.cell < 0 -> item.source
            row != null && at.widget == Spot.TEXT -> row.cells.getOrNull(at.cell)?.text ?: return null
            else -> return null
        }
        val t = source.read(text)
        val n = t.lines.size
        if (a !in 0 until n || b !in a until n) return null
        fun part(from: Int, to: Int): Typed? = if (from >= to) null else ComposedLists.slice(t, from, to)
        fun glue(vararg parts: Typed?): Typed? = parts.filterNotNull().reduceOrNull { x, y -> ComposedLists.join(x, y) }
        val block = part(a, b + 1)!!
        fun withText(d: ComposedDoc, value: String): ComposedDoc =
            if (row != null) d.setRow(at.item, row.copy(cells = row.cells.toMutableList().apply { set(at.cell, row.cells[at.cell].copy(text = value)) }))
            else d.setItem(at.item, (item as ComposedItem.Text).copy(source = value))
        // Внутри текста — с соседней строкой.
        if (by < 0 && a > 0) return Lines(withText(doc, source.write(glue(part(0, a - 1), block, part(a - 1, a), part(b + 1, n))!!)), at, a - 1)
        if (by > 0 && b < n - 1) return Lines(withText(doc, source.write(glue(part(0, a), part(b + 1, b + 2), block, part(b + 2, n))!!)), at, a + 1)
        val rest = glue(part(0, a), part(b + 1, n))
        val restText = rest?.let(source::write) ?: ""
        // Строки — в конец текста (сверху вниз пришли снизу) или в начало: номер первой перенесённой.
        fun into(old: String): Pair<String, Int> = when {
            old.isBlank() -> source.write(block) to 0
            by < 0 -> source.read(old).let { o -> source.write(ComposedLists.join(o, block)) to o.lines.size }
            else -> source.write(ComposedLists.join(block, source.read(old))) to 0
        }
        // Из ячейки — в соседнюю ячейку по порядку чтения.
        if (row != null) {
            val owners = row.cells.indices.filter { ownerOf(row, it) == it }
            val next = owners.getOrNull(owners.indexOf(at.cell) + by)
            val cells = row.cells.toMutableList().apply { set(at.cell, row.cells[at.cell].copy(text = restText)) }
            if (next != null) {
                val (joined, line) = into(cells[next].text)
                cells[next] = cells[next].copy(text = joined)
                return Lines(doc.setRow(at.item, row.copy(cells = cells)), Spot(at.item, next, Spot.TEXT), line)
            }
            return outside(doc.setRow(at.item, row.copy(cells = cells)), at.item, false, by, block, source, ::into)
        }
        // Из куска — наружу; опустевший кусок уходит.
        return outside(withText(doc, restText), at.item, rest == null, by, block, source, ::into)
    }

    /**
     * Строки [block] уходят из места [from] в сторону [by]: в соседний текст, в ряд, за значимый элемент.
     * [dropFrom] — само место опустело и уходит (кусок без строк).
     */
    private fun outside(
        doc: ComposedDoc,
        from: Int,
        dropFrom: Boolean,
        by: Int,
        block: Typed,
        source: TypedSource,
        into: (String) -> Pair<String, Int>,
    ): Lines? {
        val j = from + by
        // Из ряда на краю сообщения — новым куском рядом с рядом; из куска на краю — некуда.
        if (j !in doc.items.indices) {
            if (doc.items.getOrNull(from) !is ComposedItem.Row) return null
            val at = if (by < 0) from else from + 1
            return Lines(doc.withItems(doc.items.toMutableList().apply { add(at, ComposedItem.Text(source.write(block))) }), Spot(at), 0)
        }
        val items = doc.items.toMutableList()
        var spot: Spot
        var line = 0
        var inserted = -1
        when (val near = items[j]) {
            is ComposedItem.Text -> {
                val (joined, l) = into(near.source)
                items[j] = near.copy(source = joined)
                spot = Spot(j)
                line = l
            }
            is ComposedItem.Row -> {
                val owners = near.cells.indices.filter { ownerOf(near, it) == it }
                if (owners.isEmpty()) return null
                val enter = if (by > 0) owners.first() else owners.last()
                val (joined, l) = into(near.cells[enter].text)
                items[j] = near.copy(cells = near.cells.toMutableList().apply { set(enter, near.cells[enter].copy(text = joined)) })
                spot = Spot(j, enter, Spot.TEXT)
                line = l
            }
            else -> {
                // За значимым элементом — текст: в него; нет — новый кусок сразу за элементом.
                val k = j + by
                val beyond = items.getOrNull(k) as? ComposedItem.Text
                if (k in items.indices && beyond != null && k != from) {
                    val (joined, l) = into(beyond.source)
                    items[k] = beyond.copy(source = joined)
                    spot = Spot(k)
                    line = l
                } else {
                    inserted = if (by < 0) j else j + 1
                    items.add(inserted, ComposedItem.Text(source.write(block)))
                    spot = Spot(inserted)
                }
            }
        }
        if (dropFrom) {
            val src = if (inserted in 0..from) from + 1 else from
            items.removeAt(src)
            if (spot.item > src) spot = spot.copy(item = spot.item - 1)
        }
        return Lines(doc.withItems(items), spot, line)
    }

    /** Два набранных текста — строками друг за другом; пустой не добавляет пустую строку. */
    private fun merged(source: TypedSource, first: String, second: String): String = when {
        first.isBlank() -> second
        second.isBlank() -> first
        else -> source.write(ComposedLists.join(source.read(first), source.read(second)))
    }

    // ── Вставка на строку курсора и слияние текста ────────────────────────────

    /**
     * Куда встаёт вставленное, когда курсор в куске-тексте [item] на знаке [caret] его видимого текста
     * [typed] (заказчик 2026-10-21): на пустой строке — вместо неё, в начале непустой — над ней, иначе — под
     * строкой курсора. Кусок режется надвое; число — номер места, куда ставить вставленное.
     */
    fun gapAt(doc: ComposedDoc, item: Int, typed: Typed, caret: Int, source: TypedSource): Pair<ComposedDoc, Int> {
        val piece = doc.items.getOrNull(item) as? ComposedItem.Text ?: return doc to (item + 1).coerceIn(0, doc.items.size)
        val n = typed.lines.size
        val line = typed.lineAt(caret)
        val start = typed.lineStart(line)
        val (above, below) = when {
            start == typed.lineEnd(line) -> line to line + 1
            caret <= start -> line to line
            else -> line + 1 to line + 1
        }
        val before = if (above > 0) ComposedLists.slice(typed, 0, above) else null
        val after = if (below < n) ComposedLists.slice(typed, below, n) else null
        val items = doc.items.toMutableList()
        items.removeAt(item)
        if (after != null) items.add(item, piece.copy(source = source.write(after)))
        if (before != null) items.add(item, piece.copy(source = source.write(before)))
        return doc.copy(items = items) to item + if (before != null) 1 else 0
    }

    /** Слитый текст: документ, выбранное место и знак курсора в слитом — там, где кончался его прежний кусок. */
    data class Joined(val doc: ComposedDoc, val spot: Spot, val caret: Int?)

    /**
     * Соседние куски-тексты одного вида — один кусок (заказчик 2026-10-21): виджет между ними убрали или
     * увели — разрезу больше нечем держаться. [at] — выбранное место; курсор в слившемся тексте встаёт на
     * стык, а не в конец. Ничего не слилось — `caret` пустой.
     */
    fun joinTexts(doc: ComposedDoc, at: Spot, source: TypedSource): Joined {
        val items = mutableListOf<ComposedItem>()
        val index = IntArray(doc.items.size)
        // Слитое вплоть до выбранного куска включительно: его длина — стык.
        var upTo: String? = null
        doc.items.forEachIndexed { i, item ->
            val last = items.lastOrNull()
            if (item is ComposedItem.Text && last is ComposedItem.Text && last.style == item.style) {
                items[items.lastIndex] = last.copy(source = merged(source, last.source, item.source))
            } else {
                items += item
            }
            index[i] = items.lastIndex
            if (i == at.item && at.cell < 0) upTo = (items.last() as? ComposedItem.Text)?.source
        }
        if (items.size == doc.items.size) return Joined(doc, at, null)
        val spot = if (at.item in doc.items.indices) at.copy(item = index[at.item]) else at
        val caret = upTo?.takeIf { doc.items[at.item] != items[spot.item] }?.let { source.read(it).text.length }
        return Joined(doc.withItems(items), spot, caret)
    }

    /** Список без виджетов — строками-пунктами своих ступеней; с виджетами — `null`: в ячейку он не встаёт. */
    private fun listAsText(list: ComposedItem.ItemList, source: TypedSource): String? {
        if (list.items.isEmpty() || list.items.any { it.widgets.isNotEmpty() }) return null
        var out: Typed? = null
        for (item in list.items) {
            val kind = when (item.depth) { 1 -> LineKind.LIST1; 2 -> LineKind.LIST2; else -> LineKind.LIST3 }
            val title = source.read(item.title).let { it.copy(lines = List(it.lines.size) { kind }) }
            val one = ComposedLists.join(title, item.text.takeIf { it.isNotEmpty() }?.let(source::read))
            out = out?.let { ComposedLists.join(it, one) } ?: one
        }
        return out?.let(source::write)
    }

    /** «Удалить» выбранное: виджет, текст ячейки, содержимое ячейки или кусок целиком. */
    fun removeAt(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        // Список (ADR-0049): виджет пункта, содержимое пункта или пункт; последний пункт — со списком.
        val list = doc.items.getOrNull(at.item) as? ComposedItem.ItemList
        if (list != null && at.cell in list.items.indices) {
            val item = list.items[at.cell]
            val items = list.items.toMutableList()
            val spot = when {
                at.widget >= 0 -> {
                    items[at.cell] = item.copy(widgets = item.widgets.filterIndexed { i, _ -> i != at.widget })
                    Spot(at.item, at.cell)
                }
                at.widget == Spot.TEXT -> {
                    items[at.cell] = item.copy(text = "")
                    Spot(at.item, at.cell)
                }
                else -> {
                    items.removeAt(at.cell)
                    Spot(at.item, (at.cell - 1).coerceAtLeast(0))
                }
            }
            if (items.isEmpty()) {
                val next = doc.withItems(doc.items.toMutableList().apply { removeAt(at.item) })
                return next to Spot((at.item - 1).coerceIn(0, next.items.lastIndex))
            }
            return doc.setItem(at.item, list.copy(items = ComposedLists.normalized(items, ""))) to spot
        }
        val row = doc.row(at.item)
        if (row != null && at.cell in row.cells.indices) {
            val cell = row.cells[at.cell]
            val next = when {
                at.widget == Spot.TEXT -> cell.copy(text = "")
                at.widget >= 0 -> cell.copy(widgets = cell.widgets.filterIndexed { i, _ -> i != at.widget })
                // Ячейка остаётся местом ряда — уходит её содержимое; столбец и строку убирают «−Столбец», «−Строка».
                else -> cell.copy(text = "", widgets = emptyList(), style = emptyMap())
            }
            val cells = row.cells.toMutableList().apply { set(at.cell, next) }
            return doc.setRow(at.item, row.copy(cells = cells)) to Spot(at.item, at.cell)
        }
        if (at.item !in doc.items.indices) return doc to at
        val items = doc.items.toMutableList().apply { removeAt(at.item) }
        val next = doc.withItems(items)
        return next to Spot((at.item - 1).coerceIn(0, next.items.lastIndex))
    }

    /** Что лежит на месте — для переноса. Кусок-текст сверху вниз не переносится: его двигают «Выше», «Ниже». */
    private sealed interface Carried {
        data class W(val widget: ComposedWidget) : Carried
        data class T(val text: String) : Carried
    }

    private fun take(doc: ComposedDoc, at: Spot): Carried? {
        val item = doc.items.getOrNull(at.item) ?: return null
        return when {
            item is ComposedItem.Widget && at.cell == -1 -> Carried.W(item.widget)
            item is ComposedItem.Row && at.widget == Spot.TEXT -> item.cells.getOrNull(at.cell)?.text?.takeIf { it.isNotBlank() }?.let { Carried.T(it) }
            item is ComposedItem.Row && at.widget >= 0 -> item.cells.getOrNull(at.cell)?.widgets?.getOrNull(at.widget)?.let { Carried.W(it) }
            item is ComposedItem.ItemList && at.widget >= 0 -> item.items.getOrNull(at.cell)?.widgets?.getOrNull(at.widget)?.let { Carried.W(it) }
            else -> null
        }
    }

    /** Ячейка, которой принадлежит место [cell]: слитое — его первая ячейка. */
    fun ownerOf(row: ComposedItem.Row, cell: Int): Int {
        if (cell !in row.cells.indices) return cell
        val g = gridOf(row)
        return g.places(g.owner[cell]).first()
    }

    /**
     * Перенос виджета (Решения §10.3): палец — долгое нажатие и вести, мышь — за ручку. В другую
     * ячейку (в конец её), между кусками (виджетом во всю ширину) или на место внутри своей
     * ячейки — «Выше», «Ниже». Опустевшая ячейка остаётся.
     */
    fun move(doc: ComposedDoc, from: Spot, to: Drop, refTitle: String = ""): Pair<ComposedDoc, Spot> {
        val carried = take(doc, from) ?: return doc to from
        // Подкладка в ряд не встаёт (заказчик 2026-10-21): опущенная на ряд — ложится над ним.
        val underlay = carried is Carried.W && carried.widget is ComposedWidget.Underlay
        val asked = if (underlay && to is Drop.IntoCell) Drop.Between(to.item) else to
        // В список — только подборка и подкладка; прочее — над списком, как прежде.
        val ref = carried is Carried.W && carried.widget.isRef
        val placed = if (!ref && asked is Drop.IntoList) Drop.Between(asked.item) else asked
        val target = (placed as? Drop.IntoCell)?.let { d -> doc.row(d.item)?.let { Drop.IntoCell(d.item, ownerOf(it, d.cell)) } } ?: placed
        if (target is Drop.IntoCell && target.item == from.item && target.cell == from.cell) return doc to from
        // Сначала убрать с прежнего места — номера кусков после этого могут сдвинуться. Подборка или
        // подкладка своим пунктом списка уходит вместе с пунктом: пустой пункт «Сообщение» не нужен.
        val fromList = doc.items.getOrNull(from.item) as? ComposedItem.ItemList
        val fromItem = fromList?.items?.getOrNull(from.cell)
        val ownItem = ref && from.widget >= 0 && fromItem != null && fromItem.widgets.size == 1 && fromItem.text.isBlank()
        val (without, _) = removeAt(doc, if (ownItem) Spot(from.item, from.cell, Spot.TITLE) else from)
        val removedTopLevel = from.cell == -1
        fun shifted(i: Int) = if (removedTopLevel && from.item < i && doc.items.size == without.items.size + 1) i - 1 else i
        return when (target) {
            is Drop.IntoCell -> {
                val item = shifted(target.item)
                val row = without.row(item) ?: return doc to from
                val cell = row.cells.getOrNull(target.cell) ?: return doc to from
                val next = when (carried) {
                    is Carried.W -> cell.copy(widgets = cell.widgets + carried.widget.fresh())
                    is Carried.T -> cell.copy(text = listOf(cell.text, carried.text).filter { it.isNotBlank() }.joinToString("\n"))
                }
                val cells = row.cells.toMutableList().apply { set(target.cell, next) }
                val spot = if (carried is Carried.W) Spot(item, target.cell, next.widgets.lastIndex) else Spot(item, target.cell, Spot.TEXT)
                without.setRow(item, row.copy(cells = cells)) to spot
            }
            is Drop.Between -> {
                val at = shifted(target.before).coerceIn(0, without.items.size)
                val piece = when (carried) {
                    is Carried.W -> ComposedItem.Widget(carried.widget.fresh())
                    is Carried.T -> ComposedItem.Text(carried.text)
                }
                without.copy(items = without.items.toMutableList().apply { add(at, piece) }) to Spot(at)
            }
            is Drop.IntoList -> {
                val item = shifted(target.item)
                val list = without.items.getOrNull(item) as? ComposedItem.ItemList ?: return doc to from
                val widget = (carried as Carried.W).widget
                // Перенос внутри того же списка: номер пункта после изъятия мог сдвинуться.
                val sameList = from.item == target.item && from.cell >= 0 && list.items.size < (doc.items[from.item] as ComposedItem.ItemList).items.size
                val before = (if (sameList && from.cell < target.before) target.before - 1 else target.before).coerceIn(0, list.items.size)
                val depth = list.items.getOrNull(before)?.depth ?: list.items.lastOrNull()?.depth ?: 1
                val items = list.items.toMutableList().apply { add(before, ComposedListItem(depth, refTitle, widgets = listOf(widget))) }
                without.setItem(item, list.copy(items = ComposedLists.normalized(items, ""))) to Spot(item, before, 0)
            }
        }
    }

    /**
     * Прямоугольник слитого для показа: для каждой ячейки — сколько столбцов и строк она занимает;
     * `0 × 0` — место слито с другой и не рисуется.
     */
    fun spans(row: ComposedItem.Row): List<Pair<Int, Int>> = GridSpans.of(row.cells.map { it.merge }, row.columns)
}

/**
 * Слитое в ряду — одно правило для редактора и показа: слитая с левой принадлежит левой, с верхней —
 * верхней; ячейка рисуется прямоугольником своих мест, слитые места — `0 × 0` (не рисуются).
 */
object GridSpans {
    fun of(merges: List<String>, columns: Int): List<Pair<Int, Int>> {
        val cols = columns.coerceAtLeast(1)
        val owner = IntArray(merges.size) { it }
        for (p in merges.indices) {
            owner[p] = when {
                merges[p] == "left" && p % cols > 0 -> owner[p - 1]
                merges[p] == "up" && p >= cols -> owner[p - cols]
                else -> p
            }
        }
        return merges.indices.map { p ->
            if (owner[p] != p) {
                0 to 0
            } else {
                val places = owner.indices.filter { owner[it] == p }
                (places.maxOf { it % cols } - places.minOf { it % cols } + 1) to
                    (places.maxOf { it / cols } - places.minOf { it / cols } + 1)
            }
        }
    }
}

/**
 * Ряд всегда на одной строке (заказчик 2026-10-18): не помещается — не сохраняется. Проверка — для
 * самого узкого пузыря ([TableFit.CONTENT_DP] содержимого): текстовой ячейке нужно 35 dp («99»
 * шрифтом 22, РС-В10), картинке 96, карточке ссылки, вставленному и ответу 140, облаку, месту и
 * контакту 120, эмоджи 48 — те же наименьшие, что у показа (`RichWidgets.minWidthOf`).
 */
object RowFit {
    const val TEXT_DP = 35

    fun widgetDp(w: ComposedWidget): Int = when (w) {
        is ComposedWidget.Image -> 96
        is ComposedWidget.Link -> if (w.mode == "card") 140 else 96
        is ComposedWidget.Cloud, is ComposedWidget.Place, is ComposedWidget.Contact -> 120
        is ComposedWidget.Quoted, is ComposedWidget.Reply, is ComposedWidget.Underlay -> 140
        is ComposedWidget.Emoji -> 48
    }

    private fun cellDp(cell: ComposedCell): Int = maxOf(TEXT_DP, cell.widgets.maxOfOrNull(::widgetDp) ?: 0)

    private fun percentOf(dp: Int): Int = (dp * 100 + TableFit.CONTENT_DP - 1) / TableFit.CONTENT_DP

    /** Сколько процентов нужно столбцу [col] по ячейкам, занимающим только его. */
    fun columnNeed(row: ComposedItem.Row, col: Int): Int {
        val spans = ComposedLayout.spans(row)
        var need = 0
        for (p in row.cells.indices) {
            if (p % row.columns != col || spans[p].first != 1) continue
            need = maxOf(need, percentOf(cellDp(row.cells[p])))
        }
        return need
    }

    /** Ряд не помещается: столбцов больше семи или ячейке узко для её содержимого. */
    fun tooNarrow(row: ComposedItem.Row): Boolean {
        if (row.columns > ComposedLayout.MAX_COLUMNS) return true
        val spans = ComposedLayout.spans(row)
        for (p in row.cells.indices) {
            val (w, _) = spans[p]
            if (w == 0) continue
            val col = p % row.columns
            val width = (col until col + w).sumOf { row.cells.getOrNull(it)?.width ?: 0 }
            if (width * TableFit.CONTENT_DP < cellDp(row.cells[p]) * 100) return true
        }
        return false
    }
}
