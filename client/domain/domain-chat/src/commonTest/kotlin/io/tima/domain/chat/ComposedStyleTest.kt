package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** «Стиль» (Решения §10.2): на выбранное, JSON — только изменённое; таблица — «99» в ячейке. */
class ComposedStyleTest {

    private val doc = ComposedDoc(
        listOf(
            ComposedItem.Text("a"),
            ComposedItem.Row(listOf(ComposedCell(50, "x", style = mapOf("background" to "blue-soft")), ComposedCell(50, "y"))),
        ),
    )

    @Test
    fun стиль_ячейки_и_куска() {
        val cell = ComposedStyle.with(doc, Spot(1, 0, Spot.TEXT), "align", "center")
        assertEquals(mapOf("background" to "blue-soft", "align" to "center"), ComposedStyle.styleAt(cell, Spot(1, 0)), "текст ячейки — стиль ячейки")
        val row = ComposedStyle.with(doc, Spot(1), "divider", "2")
        assertEquals(mapOf("divider" to "2"), (row.items[1] as ComposedItem.Row).style)
        val text = ComposedStyle.with(doc, Spot(0), "color", "red")
        assertEquals(mapOf("color" to "red"), text.items[0].style)
        assertEquals(emptyMap(), ComposedStyle.styleAt(ComposedStyle.with(text, Spot(0), "color", null), Spot(0)), "убрать свойство")
        assertEquals(emptyMap(), ComposedStyle.styleAt(ComposedStyle.setStyle(doc, Spot(1, 0), emptyMap()), Spot(1, 0)), "Сброс")
        val width = ComposedStyle.setStyle(doc, Spot(1, 0), mapOf("width" to "90"))
        assertEquals(50, (width.items[1] as ComposedItem.Row).cells[0].width, "ширину стилем не поменять")
    }

    @Test
    fun json_туда_и_обратно() {
        val style = mapOf("background" to "amber-soft", "padding" to "large")
        assertEquals(style, ComposedStyle.fromJson(ComposedStyle.toJson(style)))
        assertEquals(emptyMap(), ComposedStyle.fromJson(" { } "))
        assertEquals(mapOf("a" to "b\"c"), ComposedStyle.fromJson("""{"a": "b\"c"}"""))
        assertNull(ComposedStyle.fromJson("""{"a": 1}"""), "только строки")
        assertNull(ComposedStyle.fromJson("не json"))
        assertNull(ComposedStyle.fromJson("""{"a": "b" "c": "d"}"""))
    }

    @Test
    fun таблица_99_в_ячейке() {
        assertEquals(7, TableFit.maxColumns())
        assertEquals(3, TableFit.maxColumns(50))
        val seven = "| " + (1..7).joinToString(" | ") + " |\n|" + List(7) { "---" }.joinToString("|") + "|\n| a |"
        val eight = "| " + (1..8).joinToString(" | ") + " |\n|" + List(8) { "---" }.joinToString("|") + "|"
        assertEquals(7, TableFit.widest(seven))
        assertEquals(8, TableFit.widest(eight))
        assertEquals(2, TableFit.widest("| a \\| b | c |\n|---|---|"), "экранированная черта — не граница")
        assertEquals(0, TableFit.widest("| не таблица |"))
        assertEquals(listOf(1), TableFit.tooWide(ComposedDoc(listOf(ComposedItem.Text(seven), ComposedItem.Text(eight)))))
    }
}
