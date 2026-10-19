package io.tima.domain.chat

/**
 * Список в документе редактора (ADR-0049 §4): строки-пункты куска-текста становятся куском «Список»,
 * пункт обратно — строкой текста; перевод строки в заголовке пункта; ступени. Чистые функции над
 * документом — как [ComposedLayout] у рядов.
 */
object ComposedLists {

    const val MAX_DEPTH = 3

    private val LISTS = listOf(LineKind.LIST1, LineKind.LIST2, LineKind.LIST3)

    /**
     * Ступени по порядку: первый пункт — 1; пункт глубже соседа сверху больше чем на ступень —
     * над ним пункты [untitled] (заказчик 2026-10-19). Пусто [untitled] — пункт встаёт на ступень
     * ниже соседа.
     */
    fun normalized(items: List<ComposedListItem>, untitled: String): List<ComposedListItem> {
        val out = mutableListOf<ComposedListItem>()
        var prev = 0
        for (item in items) {
            var d = item.depth.coerceIn(1, MAX_DEPTH)
            if (untitled.isEmpty()) d = minOf(d, prev + 1)
            while (d > prev + 1) {
                prev++
                out += ComposedListItem(prev, untitled)
            }
            out += item.copy(depth = d)
            prev = d
        }
        return out
    }

    /** Строки [from] until [to] набранного — своим набранным. */
    fun slice(t: Typed, from: Int, to: Int): Typed {
        val a = t.lineStart(from)
        val b = if (to >= t.lines.size) t.text.length else t.lineStart(to) - 1
        return cut(t, a, b).copy(lines = t.lines.subList(from, to))
    }

    /** Знаки [a]…[b] одной строки — своим набранным абзацем. */
    fun cut(t: Typed, a: Int, b: Int): Typed {
        val text = t.text.substring(a, b)
        val spans = t.spans.filter { it.end > a && it.start < b }.map { it.copy(start = maxOf(it.start, a) - a, end = minOf(it.end, b) - a) }
        return TypedEdits.normalized(Typed(text, List(text.count { it == '\n' } + 1) { LineKind.PARAGRAPH }, spans))
    }

    /** Склеить набранное строками: [first], затем [rest]. */
    fun join(first: Typed, rest: Typed?): Typed {
        if (rest == null) return first
        val base = first.text.length + 1
        return Typed(first.text + "\n" + rest.text, first.lines + rest.lines, first.spans + rest.spans.map { it.copy(start = it.start + base, end = it.end + base) })
    }

    /** Где курсор после правки документа: кусок, место в нём и знак в видимом тексте. */
    data class Moved(val doc: ComposedDoc, val spot: Spot, val cursor: Int)

    /**
     * Строки-пункты кусков-текстов → кусок «Список» (ADR-0049 §4), соседние списки — одним. Курсор
     * был в [at] на месте [cursor] видимого текста — [Moved] говорит, где он теперь.
     */
    fun lift(doc: ComposedDoc, source: TypedSource, untitled: String, at: Spot, cursor: Int): Moved {
        val out = mutableListOf<ComposedItem>()
        var spot = at
        var pos = cursor

        /** Добавить кусок; список за списком — сливаются. Возвращает, со скольки пунктов начались его пункты. */
        fun add(item: ComposedItem): Int {
            val prev = out.lastOrNull()
            if (item is ComposedItem.ItemList && prev is ComposedItem.ItemList) {
                out[out.lastIndex] = prev.copy(items = prev.items + item.items)
                return prev.items.size
            }
            out += item
            return 0
        }

        doc.items.forEachIndexed { index, item ->
            val typed = (item as? ComposedItem.Text)?.let { source.read(it.source) }
            if (typed == null || typed.lines.none { it in LISTS }) {
                val base = add(item)
                if (at.item == index) spot = if (item is ComposedItem.ItemList && at.cell >= 0) at.copy(item = out.lastIndex, cell = at.cell + base) else at.copy(item = out.lastIndex)
                return@forEachIndexed
            }
            item as ComposedItem.Text
            val here = at.item == index && at.cell == -1
            val line = if (here) typed.lineAt(cursor) else -1
            var i = 0
            while (i < typed.lines.size) {
                val list = typed.lines[i] in LISTS
                var j = i
                while (j < typed.lines.size && (typed.lines[j] in LISTS) == list) j++
                if (list) {
                    val items = (i until j).map { k ->
                        val title = slice(typed, k, k + 1).copy(lines = listOf(LineKind.PARAGRAPH))
                        ComposedListItem(LISTS.indexOf(typed.lines[k]) + 1, source.write(title))
                    }
                    val base = add(ComposedItem.ItemList(items))
                    if (line in i until j) {
                        spot = Spot(out.lastIndex, base + (line - i), Spot.TITLE)
                        pos = cursor - typed.lineStart(line)
                    }
                } else {
                    val part = slice(typed, i, j)
                    // Пустой остаток без курсора не нужен: писать есть где.
                    if (part.text.isNotEmpty() || line in i until j) {
                        add(ComposedItem.Text(source.write(part), item.style))
                        if (line in i until j) {
                            spot = Spot(out.lastIndex)
                            pos = cursor - typed.lineStart(i)
                        }
                    }
                }
                i = j
            }
        }
        // Ступени по порядку; вставленные пункты сдвигают пункт с курсором.
        val items = out.mapIndexed { index, item ->
            if (item !is ComposedItem.ItemList) return@mapIndexed item
            val fixed = normalized(item.items, untitled)
            if (spot.item == index && spot.cell >= 0 && fixed.size != item.items.size) {
                val before = normalized(item.items.subList(0, spot.cell + 1), untitled).size - 1
                spot = spot.copy(cell = before)
            }
            item.copy(items = fixed)
        }
        return Moved(doc.copy(items = items.ifEmpty { listOf(ComposedItem.Text("")) }), spot, pos)
    }

    /**
     * Пункт [at] — строкой текста вида [kind] («Абзац ▾» в заголовке): заголовок — первой строкой,
     * содержимое — под ней, виджеты — следом; список делится на до и после.
     */
    fun toText(doc: ComposedDoc, at: Spot, kind: LineKind, source: TypedSource): Moved? {
        val list = doc.items.getOrNull(at.item) as? ComposedItem.ItemList ?: return null
        val item = list.items.getOrNull(at.cell) ?: return null
        val before = list.items.subList(0, at.cell)
        val after = list.items.subList(at.cell + 1, list.items.size)
        val title = source.read(item.title).let { it.copy(lines = List(it.lines.size) { kind }) }
        val typed = join(title, item.text.takeIf { it.isNotEmpty() }?.let(source::read))
        val pieces = mutableListOf<ComposedItem>()
        if (before.isNotEmpty()) pieces += list.copy(items = before)
        pieces += ComposedItem.Text(source.write(typed))
        val textAt = at.item + pieces.lastIndex
        item.widgets.forEach { pieces += ComposedItem.Widget(it) }
        if (after.isNotEmpty()) pieces += list.copy(items = normalized(after, ""))
        val items = doc.items.subList(0, at.item) + pieces + doc.items.subList(at.item + 1, doc.items.size)
        return Moved(doc.copy(items = items), Spot(textAt), 0)
    }

    /** Ступень пункта [at] — [depth] («Абзац ▾ → Список», «Список 2»); без родителя — над ним [untitled]. */
    fun depth(doc: ComposedDoc, at: Spot, depth: Int, untitled: String): Moved? {
        val list = doc.items.getOrNull(at.item) as? ComposedItem.ItemList ?: return null
        if (at.cell !in list.items.indices) return null
        val items = list.items.toMutableList().apply { set(at.cell, get(at.cell).copy(depth = depth)) }
        val fixed = normalized(items, untitled)
        val cell = normalized(items.subList(0, at.cell + 1), untitled).size - 1
        return Moved(doc.copy(items = doc.items.toMutableList().apply { set(at.item, list.copy(items = fixed)) }), at.copy(cell = cell), 0)
    }

    /**
     * Перевод строки в заголовке пункта [at] на месте [cursor] (Word): пустой пункт — ступенью выше,
     * на первой ступени — конец списка, под ним новый текст; иначе — новый пункт той же ступени с
     * тем, что за курсором. Содержимое и виджеты остаются у прежнего пункта.
     */
    fun enter(doc: ComposedDoc, at: Spot, title: Typed, cursor: Int, source: TypedSource): Moved? {
        val list = doc.items.getOrNull(at.item) as? ComposedItem.ItemList ?: return null
        val item = list.items.getOrNull(at.cell) ?: return null
        fun next(items: List<ComposedItem>) = doc.copy(items = items)
        if (title.text.isEmpty()) {
            if (item.depth > 1) {
                val items = list.items.toMutableList().apply { set(at.cell, item.copy(depth = item.depth - 1)) }
                return Moved(next(doc.items.toMutableList().apply { set(at.item, list.copy(items = normalized(items, ""))) }), at, 0)
            }
            val before = list.items.subList(0, at.cell)
            val after = list.items.subList(at.cell + 1, list.items.size)
            val pieces = mutableListOf<ComposedItem>()
            if (before.isNotEmpty()) pieces += list.copy(items = before)
            // Пустой заголовок уходит; содержимое пункта — текстом под списком.
            pieces += ComposedItem.Text(item.text)
            val textAt = at.item + pieces.lastIndex
            item.widgets.forEach { pieces += ComposedItem.Widget(it) }
            if (after.isNotEmpty()) pieces += list.copy(items = normalized(after, ""))
            return Moved(next(doc.items.subList(0, at.item) + pieces + doc.items.subList(at.item + 1, doc.items.size)), Spot(textAt), 0)
        }
        val c = cursor.coerceIn(0, title.text.length)
        val left = cut(title, 0, c)
        val right = cut(title, c, title.text.length)
        val items = list.items.toMutableList().apply {
            set(at.cell, item.copy(title = source.write(left)))
            add(at.cell + 1, ComposedListItem(item.depth, source.write(right)))
        }
        return Moved(next(doc.items.toMutableList().apply { set(at.item, list.copy(items = items)) }), Spot(at.item, at.cell + 1, Spot.TITLE), 0)
    }
}

/**
 * Пункт списка не помещается (ADR-0049 §3): виджету узко с отступом ступени и местом под стрелку —
 * те же, что у показа (`RichBody.BulletList`, 14 dp на ступень); без отступа — только стрелка.
 */
object ListFit {
    const val STEP_DP = 14

    fun tooNarrow(list: ComposedItem.ItemList): Boolean = list.items.any { item ->
        val room = TableFit.CONTENT_DP - STEP_DP - if (list.flat) 0 else STEP_DP * item.depth
        item.widgets.any { RowFit.widgetDp(it) > room }
    }
}
