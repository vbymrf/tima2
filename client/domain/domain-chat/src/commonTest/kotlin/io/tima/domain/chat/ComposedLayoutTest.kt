package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** «Разметка» (Решения §10.3): ряды, ячейки, объединение, выше и ниже, удаление, перенос. */
class ComposedLayoutTest {

    private val emoji = ComposedWidget.Emoji("🎉")
    private val place = ComposedWidget.Place(55.75, 37.61)

    private fun ComposedDoc.row(i: Int) = items[i] as ComposedItem.Row

    @Test
    fun ширины_поровну_в_сумме_100() {
        assertEquals(listOf(100), ComposedLayout.widths(1))
        assertEquals(listOf(34, 33, 33), ComposedLayout.widths(3))
        assertEquals(listOf(25, 25, 25, 25), ComposedLayout.widths(4))
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
    fun ячейки_добавляются_до_четырёх_и_убираются() {
        var doc = ComposedDoc(listOf(ComposedItem.Row(listOf(ComposedCell(50, "a"), ComposedCell(50, "b")))))
        repeat(3) { doc = ComposedLayout.addCell(doc, Spot(0, 0)).first }
        assertEquals(4, doc.row(0).cells.size, "не больше четырёх")
        assertEquals(listOf("a", "", "", "b"), doc.row(0).cells.map { it.text })
        assertEquals(100, doc.row(0).cells.sumOf { it.width })
        repeat(3) { doc = ComposedLayout.removeCell(doc, Spot(0, 1)).first }
        assertEquals(listOf("a"), doc.row(0).cells.map { it.text })
        assertEquals(listOf(100), doc.row(0).cells.map { it.width })
        doc = ComposedLayout.removeCell(doc, Spot(0, 0)).first
        assertIs<ComposedItem.Text>(doc.items.single(), "последняя ячейка — ряд убран, писать есть куда")
    }

    @Test
    fun объединение_и_разъединение() {
        val doc = ComposedDoc(listOf(ComposedItem.Row(listOf(ComposedCell(30, "a", listOf(emoji)), ComposedCell(70, "b", listOf(place))))))
        val (merged, spot) = ComposedLayout.merge(doc, Spot(0, 0))
        val cell = merged.row(0).cells.single()
        assertEquals(100, cell.width)
        assertEquals("a\nb", cell.text)
        assertEquals(listOf(emoji, place), cell.widgets)
        assertEquals(Spot(0, 0), spot)
        val (split, _) = ComposedLayout.split(merged, Spot(0, 0))
        assertEquals(listOf(50, 50), split.row(0).cells.map { it.width })
        assertEquals("a\nb", split.row(0).cells[0].text, "содержимое — в левой")
    }

    @Test
    fun выше_ниже_для_виджета_ячейки_и_куска() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Text("t"),
                ComposedItem.Row(listOf(ComposedCell(50, widgets = listOf(emoji, place)), ComposedCell(50, "x"))),
            ),
        )
        val (w, ws) = ComposedLayout.shift(doc, Spot(1, 0, 1), -1)
        assertEquals(listOf(place, emoji), w.row(1).cells[0].widgets)
        assertEquals(Spot(1, 0, 0), ws)
        val (c, _) = ComposedLayout.shift(doc, Spot(1, 1), -1)
        assertEquals("x", c.row(1).cells[0].text)
        val (i, at) = ComposedLayout.shift(doc, Spot(1), -1)
        assertIs<ComposedItem.Row>(i.items[0])
        assertEquals(Spot(0), at)
        assertEquals(doc to Spot(0), ComposedLayout.shift(doc, Spot(0), -1), "выше первого — некуда")
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
    fun пустые_ячейки_не_уходят() {
        val row = ComposedItem.Row(listOf(ComposedCell(25, "a"), ComposedCell(50), ComposedCell(25, "b")))
        val compact = ComposedLayout.compact(row)
        assertEquals(listOf("a", "b"), compact.cells.map { it.text })
        assertEquals(listOf(50, 50), compact.cells.map { it.width })
    }
}
