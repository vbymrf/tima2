package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * «Разметка» (Решения §10.3; заказчик 2026-10-18): ряд строк × столбцов, столбцы и строки, слияние
 * в оба направления, ширина рамкой, выше и ниже, удаление, перенос.
 */
class ComposedLayoutTest {

    private val emoji = ComposedWidget.Emoji("🎉")
    private val place = ComposedWidget.Place(55.75, 37.61)
    private val picture = ComposedWidget.Image(OutMedia("m", ByteArray(1), "image/jpeg", 100, 100))

    private fun ComposedDoc.row(i: Int) = items[i] as ComposedItem.Row

    private fun grid(lines: Int, columns: Int) = ComposedLayout.addRow(ComposedDoc(listOf(ComposedItem.Text("a"))), Spot(0), lines, columns).first

    @Test
    fun ширины_поровну_в_сумме_100() {
        assertEquals(listOf(100), ComposedLayout.widths(1))
        assertEquals(listOf(34, 33, 33), ComposedLayout.widths(3))
        assertEquals(listOf(15, 15, 14, 14, 14, 14, 14), ComposedLayout.widths(7))
    }

    @Test
    fun ряд_строк_и_столбцов_равными_ячейками() {
        val doc = grid(3, 4)
        val row = doc.row(1)
        assertEquals(4, row.columns)
        assertEquals(3, row.lines)
        assertEquals(12, row.cells.size)
        assertEquals(List(3) { listOf(25, 25, 25, 25) }.flatten(), row.cells.map { it.width })
        assertTrue(!doc.isEmpty, "пустой ряд — тоже содержимое: уходит, фон виден")
    }

    @Test
    fun ряд_из_виджета_и_после_текста() {
        val doc = ComposedDoc(listOf(ComposedItem.Text("a"), ComposedItem.Widget(emoji)))
        val (wrapped, spot) = ComposedLayout.addRow(doc, Spot(1))
        assertEquals(listOf(emoji), wrapped.row(1).cells[0].widgets, "виджет стал первой ячейкой")
        assertEquals(Spot(1, 1), spot, "курсор — во второй, пустой")
        val (added, at) = ComposedLayout.addRow(doc, Spot(0))
        assertEquals(3, added.items.size)
        assertEquals(listOf(50, 50), added.row(1).cells.map { it.width })
        assertEquals(Spot(1, 0), at)
    }

    @Test
    fun столбцы_до_семи_строки_до_двадцати() {
        var doc = grid(2, 2)
        repeat(6) { doc = ComposedLayout.addColumn(doc, Spot(1, 0)).first }
        assertEquals(7, doc.row(1).columns, "не больше семи")
        assertEquals(14, doc.row(1).cells.size)
        assertTrue(doc.row(1).cells.take(7).sumOf { it.width } == 100)
        repeat(25) { doc = ComposedLayout.addLine(doc, Spot(1, 0)).first }
        assertEquals(20, doc.row(1).lines, "не больше двадцати")
        repeat(19) { doc = ComposedLayout.removeLine(doc, Spot(1, 0)).first }
        assertEquals(1, doc.row(1).lines)
        repeat(6) { doc = ComposedLayout.removeColumn(doc, Spot(1, 0)).first }
        assertEquals(listOf(100), doc.row(1).cells.map { it.width })
        doc = ComposedLayout.removeColumn(doc, Spot(1, 0)).first
        assertIs<ComposedItem.Text>(doc.items.single(), "последний столбец — ряд убран")
    }

    @Test
    fun слияние_с_левой_и_с_верхней_прямоугольником() {
        var doc = grid(2, 2)
        doc = doc.copy(items = listOf(doc.items[0], doc.row(1).let { r -> r.copy(cells = r.cells.mapIndexed { i, c -> c.copy(text = "t$i") }) }))
        val (left, at) = ComposedLayout.join(doc, Spot(1, 1), "left")!!
        assertEquals(listOf("", "left", "", ""), left.row(1).cells.map { it.merge })
        assertEquals("t0\nt1", left.row(1).cells[0].text, "содержимое перешло в соседнюю")
        assertEquals(Spot(1, 0), at)
        // Нижняя левая — с верхней: слитое должно остаться прямоугольником — захватывает и нижнюю правую.
        val (all, _) = ComposedLayout.join(left, Spot(1, 2), "up")!!
        assertEquals(listOf("", "left", "up", "left"), all.row(1).cells.map { it.merge }, "слева тот же владелец — «с левой», иначе «с верхней»")
        assertEquals("t0\nt1\nt2\nt3", all.row(1).cells[0].text)
        assertEquals(listOf(2 to 2, 0 to 0, 0 to 0, 0 to 0), ComposedLayout.spans(all.row(1)))
        assertNull(ComposedLayout.join(doc, Spot(1, 0), "left"), "в первом столбце слева не с чем")
        val (split, _) = ComposedLayout.split(all, Spot(1, 0))
        assertEquals(listOf("", "", "", ""), split.row(1).cells.map { it.merge })
        assertEquals("t0\nt1\nt2\nt3", split.row(1).cells[0].text, "содержимое — в первой")
    }

    @Test
    fun частичное_пересечение_слитого_нельзя() {
        // 2 × 3: сверху слиты 0 и 1, снизу — 4 и 5.
        var doc = grid(2, 3)
        doc = ComposedLayout.join(doc, Spot(1, 1), "left")!!.first
        doc = ComposedLayout.join(doc, Spot(1, 5), "left")!!.first
        // Нижняя левая с верхней: прямоугольник 2 × 2 задел бы нижнее слитое только половиной.
        assertNull(ComposedLayout.join(doc, Spot(1, 3), "up"))
    }

    @Test
    fun столбец_внутри_слитого_расширяет_его() {
        var doc = grid(1, 2)
        doc = ComposedLayout.join(doc, Spot(1, 1), "left")!!.first
        doc = ComposedLayout.addColumn(doc, Spot(1, 0)).first
        assertEquals(listOf("", "left", "left"), doc.row(1).cells.map { it.merge }, "новый столбец внутри слитого — в нём")
        assertEquals(listOf(34, 33, 33), doc.row(1).cells.map { it.width })
    }

    @Test
    fun рамка_перераспределяет_между_соседними() {
        var doc = grid(1, 3)
        doc = ComposedLayout.resize(doc, 1, boundary = 1, delta = 10)
        assertEquals(listOf(44, 23, 33), doc.row(1).cells.map { it.width }, "третий столбец не тронут")
        doc = ComposedLayout.resize(doc, 1, boundary = 1, delta = 50)
        assertEquals(listOf(52, 15, 33), doc.row(1).cells.map { it.width }, "не уже 15 % при трёх столбцах")
        // Картинке нужно 96 dp из 260 — 37 %: уже рамка не сдвинется.
        val row = doc.row(1)
        doc = doc.copy(items = listOf(doc.items[0], row.copy(cells = row.cells.mapIndexed { i, c -> if (i == 0) c.copy(widgets = listOf(picture)) else c })))
        doc = ComposedLayout.resize(doc, 1, boundary = 1, delta = -40)
        assertEquals(37, doc.row(1).cells[0].width)
    }

    @Test
    fun не_помещается_ряд_красный() {
        val narrow = ComposedItem.Row(listOf(ComposedCell(20, widgets = listOf(picture)), ComposedCell(80)))
        assertTrue(RowFit.tooNarrow(narrow), "картинке 20 % узко")
        assertEquals(listOf(0), TableFit.tooWide(ComposedDoc(listOf(narrow))))
        val fine = ComposedItem.Row(listOf(ComposedCell(40, widgets = listOf(picture)), ComposedCell(60)))
        assertTrue(!RowFit.tooNarrow(fine))
        // Слитая на два столбца картинка — ширина двух.
        val wide = ComposedItem.Row(listOf(ComposedCell(20, widgets = listOf(picture)), ComposedCell(20, merge = "left"), ComposedCell(60)), columns = 3)
        assertTrue(!RowFit.tooNarrow(wide))
    }

    @Test
    fun выше_ниже_для_виджета_и_куска() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Text("t"),
                ComposedItem.Row(listOf(ComposedCell(50, widgets = listOf(emoji, place)), ComposedCell(50, "x"))),
            ),
        )
        val (w, ws) = ComposedLayout.shift(doc, Spot(1, 0, 1), -1)
        assertEquals(listOf(place, emoji), w.row(1).cells[0].widgets)
        assertEquals(Spot(1, 0, 0), ws)
        assertEquals(doc to Spot(1, 1), ComposedLayout.shift(doc, Spot(1, 1), -1), "ячейки местами не меняются")
        val (i, at) = ComposedLayout.shift(doc, Spot(1), -1)
        assertIs<ComposedItem.Row>(i.items[0])
        assertEquals(Spot(0), at)
        assertEquals(doc to Spot(0), ComposedLayout.shift(doc, Spot(0), -1), "выше первого — некуда")
    }

    @Test
    fun удалить_ячейку_значит_очистить() {
        val doc = ComposedDoc(listOf(ComposedItem.Row(listOf(ComposedCell(50, "a", listOf(emoji), mapOf("background" to "amber-soft")), ComposedCell(50, "b")))))
        val (cleared, _) = ComposedLayout.removeAt(doc, Spot(0, 0))
        assertEquals(2, cleared.row(0).cells.size, "место в ряду остаётся")
        assertEquals(ComposedCell(50), cleared.row(0).cells[0])
    }

    @Test
    fun перенос_в_ячейку_между_кусками_и_текста_ячейки() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Widget(emoji),
                ComposedItem.Row(listOf(ComposedCell(50, "a"), ComposedCell(50, "b", listOf(place)))),
                ComposedItem.Text("end"),
            ),
        )
        val (into, spot) = ComposedLayout.move(doc, Spot(0), Drop.IntoCell(1, 0))
        assertEquals(2, into.items.size, "виджет во всю ширину ушёл в ячейку")
        assertEquals(listOf(emoji), into.row(0).cells[0].widgets)
        assertEquals(Spot(0, 0, 0), spot)

        val (out, outSpot) = ComposedLayout.move(doc, Spot(1, 1, 0), Drop.Between(3))
        assertEquals(place, (out.items[3] as ComposedItem.Widget).widget)
        assertTrue(out.row(1).cells[1].widgets.isEmpty(), "опустевшая ячейка остаётся")
        assertEquals("b", out.row(1).cells[1].text)
        assertEquals(Spot(3), outSpot)

        val (text, _) = ComposedLayout.move(doc, Spot(1, 0, Spot.TEXT), Drop.IntoCell(1, 1))
        assertEquals("b\na", text.row(1).cells[1].text, "текст ячейки — виджетом «Текст»: дописан к тексту соседней")
        assertEquals("", text.row(1).cells[0].text)
    }

    @Test
    fun перенос_в_слитое_попадает_в_первую_ячейку() {
        var doc = grid(1, 2)
        doc = ComposedLayout.join(doc, Spot(1, 1), "left")!!.first
        doc = doc.copy(items = listOf(ComposedItem.Widget(emoji)) + doc.items.drop(1))
        val (into, spot) = ComposedLayout.move(doc, Spot(0), Drop.IntoCell(1, 1))
        assertEquals(listOf(emoji), into.row(0).cells[0].widgets)
        assertEquals(Spot(0, 0, 0), spot)
    }
}
