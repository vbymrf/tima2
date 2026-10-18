package io.tima.domain.chat

/**
 * Место в документе редактора: кусок сверху вниз, ячейка ряда, виджет в ячейке. [cell] `-1` —
 * кусок целиком; [widget] `-1` — ячейка целиком, [TEXT] — текст ячейки (Решения §10.3: написанное в
 * ячейке становится виджетом «Текст» с ручкой).
 */
data class Spot(val item: Int, val cell: Int = -1, val widget: Int = -1) {
    companion object {
        const val TEXT = -2
    }
}

/** Куда опустили перенесённое: в ячейку (в конец) или между кусками сверху вниз. */
sealed interface Drop {
    data class IntoCell(val item: Int, val cell: Int) : Drop
    /** Перед куском [before]; `items.size` — в самый низ. */
    data class Between(val before: Int) : Drop
}

/**
 * Раздел «Разметка» (Решения §10.2–§10.3): ряд, ячейки, объединение, выше и ниже, удаление,
 * перенос. Чистые функции над документом — их проверяют тесты, а экран только зовёт.
 *
 * Ширины ячеек в ряду в сумме 100 (ADR-0044 §5): добавили или убрали ячейку — ширины
 * выравниваются поровну, объединили — сложились.
 */
object ComposedLayout {

    const val MAX_CELLS = 4

    /** Поровну, остаток — первым: 3 ячейки — 34, 33, 33. */
    fun widths(n: Int): List<Int> = List(n) { i -> 100 / n + if (i < 100 % n) 1 else 0 }

    private fun ComposedDoc.withItems(items: List<ComposedItem>) = copy(items = items.ifEmpty { listOf(ComposedItem.Text("")) })

    private fun ComposedDoc.row(at: Int): ComposedItem.Row? = items.getOrNull(at) as? ComposedItem.Row

    private fun ComposedDoc.setRow(at: Int, row: ComposedItem.Row) = copy(items = items.toMutableList().apply { set(at, row) })

    private fun ComposedItem.Row.evened(cells: List<ComposedCell>) =
        copy(cells = cells.zip(widths(cells.size)) { c, w -> c.copy(width = w) })

    /**
     * «Ряд»: после выбранного — ряд из двух ячеек. Выбран виджет во всю ширину — он сам становится
     * первой ячейкой ряда: так собирают «картинка слева, текст справа».
     */
    fun addRow(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val item = doc.items.getOrNull(at.item)
        if (item is ComposedItem.Widget) {
            val row = ComposedItem.Row(listOf(ComposedCell(50, widgets = listOf(item.widget)), ComposedCell(50)), item.style)
            return doc.setRow(at.item, row) to Spot(at.item, 1)
        }
        val index = (at.item + 1).coerceIn(0, doc.items.size)
        val items = doc.items.toMutableList().apply { add(index, ComposedItem.Row(listOf(ComposedCell(50), ComposedCell(50)))) }
        return doc.withItems(items) to Spot(index, 0)
    }

    /** «+Ячейка»: после выбранной (или в конец), не больше четырёх. */
    fun addCell(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        if (row.cells.size >= MAX_CELLS) return doc to at
        val index = if (at.cell >= 0) at.cell + 1 else row.cells.size
        val cells = row.cells.toMutableList().apply { add(index, ComposedCell(0)) }
        return doc.setRow(at.item, row.evened(cells)) to Spot(at.item, index)
    }

    /** «−Ячейка»: выбранную (или последнюю). Последняя ячейка ряда — ряд убирается целиком. */
    fun removeCell(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        val index = if (at.cell in row.cells.indices) at.cell else row.cells.lastIndex
        if (row.cells.size <= 1) return removeAt(doc, Spot(at.item))
        val cells = row.cells.toMutableList().apply { removeAt(index) }
        return doc.setRow(at.item, row.evened(cells)) to Spot(at.item, (index - 1).coerceAtLeast(0))
    }

    /** «Объедин.»: выбранная ячейка с соседней справа — ширины складываются, текст и виджеты подряд. */
    fun merge(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        val i = at.cell.coerceAtLeast(0)
        if (i + 1 > row.cells.lastIndex) return doc to at
        val a = row.cells[i]
        val b = row.cells[i + 1]
        val joined = ComposedCell(
            width = a.width + b.width,
            text = listOf(a.text, b.text).filter { it.isNotBlank() }.joinToString("\n"),
            widgets = a.widgets + b.widgets,
            style = a.style,
        )
        val cells = row.cells.toMutableList().apply { set(i, joined); removeAt(i + 1) }
        return doc.setRow(at.item, row.copy(cells = cells)) to Spot(at.item, i)
    }

    /** «Разъедин.»: ячейка — на две половины ширины; содержимое остаётся в левой. */
    fun split(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item) ?: return doc to at
        val i = at.cell.coerceAtLeast(0)
        val cell = row.cells.getOrNull(i) ?: return doc to at
        if (row.cells.size >= MAX_CELLS || cell.width < 2) return doc to at
        val left = cell.width - cell.width / 2
        val cells = row.cells.toMutableList().apply {
            set(i, cell.copy(width = left))
            add(i + 1, ComposedCell(cell.width - left))
        }
        return doc.setRow(at.item, row.copy(cells = cells)) to Spot(at.item, i + 1)
    }

    /**
     * «Выше» и «Ниже» (−1, +1): виджет внутри ячейки — среди соседей по ячейке; ячейка — среди ячеек
     * ряда; кусок — среди кусков.
     */
    fun shift(doc: ComposedDoc, at: Spot, by: Int): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item)
        if (row != null && at.cell in row.cells.indices && at.widget >= 0) {
            val cell = row.cells[at.cell]
            val to = at.widget + by
            if (to !in cell.widgets.indices) return doc to at
            val widgets = cell.widgets.toMutableList().apply { add(to, removeAt(at.widget)) }
            val cells = row.cells.toMutableList().apply { set(at.cell, cell.copy(widgets = widgets)) }
            return doc.setRow(at.item, row.copy(cells = cells)) to at.copy(widget = to)
        }
        if (row != null && at.cell in row.cells.indices) {
            val to = at.cell + by
            if (to !in row.cells.indices) return doc to at
            val cells = row.cells.toMutableList().apply { add(to, removeAt(at.cell)) }
            return doc.setRow(at.item, row.copy(cells = cells)) to at.copy(cell = to)
        }
        val to = at.item + by
        if (at.item !in doc.items.indices || to !in doc.items.indices) return doc to at
        return doc.copy(items = doc.items.toMutableList().apply { add(to, removeAt(at.item)) }) to Spot(to)
    }

    /** «Удалить» выбранное: виджет, текст ячейки, ячейку или кусок целиком. */
    fun removeAt(doc: ComposedDoc, at: Spot): Pair<ComposedDoc, Spot> {
        val row = doc.row(at.item)
        if (row != null && at.cell in row.cells.indices && at.widget != -1) {
            val cell = row.cells[at.cell]
            val next = if (at.widget == Spot.TEXT) cell.copy(text = "") else cell.copy(widgets = cell.widgets.filterIndexed { i, _ -> i != at.widget })
            // Опустевшая ячейка остаётся (Решения §10.3): пустые при отправке не уходят.
            val cells = row.cells.toMutableList().apply { set(at.cell, next) }
            return doc.setRow(at.item, row.copy(cells = cells)) to Spot(at.item, at.cell)
        }
        if (row != null && at.cell in row.cells.indices) return removeCell(doc, Spot(at.item, at.cell))
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
            else -> null
        }
    }

    /**
     * Перенос виджета (Решения §10.3): палец — долгое нажатие и вести, мышь — за ручку. В другую
     * ячейку (в конец её), между кусками (виджетом во всю ширину) или на место внутри своей
     * ячейки — «Выше», «Ниже». Опустевшая ячейка остаётся.
     */
    fun move(doc: ComposedDoc, from: Spot, to: Drop): Pair<ComposedDoc, Spot> {
        val carried = take(doc, from) ?: return doc to from
        if (to is Drop.IntoCell && to.item == from.item && to.cell == from.cell) return doc to from
        // Сначала убрать с прежнего места — номера кусков после этого могут сдвинуться.
        val (without, _) = removeAt(doc, from)
        val removedTopLevel = from.cell == -1
        fun shifted(i: Int) = if (removedTopLevel && from.item < i && doc.items.size == without.items.size + 1) i - 1 else i
        return when (to) {
            is Drop.IntoCell -> {
                val item = shifted(to.item)
                val row = without.row(item) ?: return doc to from
                val cell = row.cells.getOrNull(to.cell) ?: return doc to from
                val next = when (carried) {
                    is Carried.W -> cell.copy(widgets = cell.widgets + carried.widget)
                    is Carried.T -> cell.copy(text = listOf(cell.text, carried.text).filter { it.isNotBlank() }.joinToString("\n"))
                }
                val cells = row.cells.toMutableList().apply { set(to.cell, next) }
                val spot = if (carried is Carried.W) Spot(item, to.cell, next.widgets.lastIndex) else Spot(item, to.cell, Spot.TEXT)
                without.setRow(item, row.copy(cells = cells)) to spot
            }
            is Drop.Between -> {
                val at = shifted(to.before).coerceIn(0, without.items.size)
                val piece = when (carried) {
                    is Carried.W -> ComposedItem.Widget(carried.widget)
                    is Carried.T -> ComposedItem.Text(carried.text)
                }
                without.copy(items = without.items.toMutableList().apply { add(at, piece) }) to Spot(at)
            }
        }
    }

    /** Ряд к отправке: пустые ячейки не уходят, ширины оставшихся — снова в сумме 100. */
    fun compact(row: ComposedItem.Row): ComposedItem.Row {
        val kept = row.cells.filterNot { it.isEmpty }
        if (kept.size == row.cells.size || kept.isEmpty()) return row
        val total = kept.sumOf { it.width }.coerceAtLeast(1)
        val scaled = kept.map { it.copy(width = (it.width * 100 / total).coerceAtLeast(1)) }
        val fix = 100 - scaled.sumOf { it.width }
        return row.copy(cells = scaled.mapIndexed { i, c -> if (i == 0) c.copy(width = c.width + fix) else c })
    }
}
