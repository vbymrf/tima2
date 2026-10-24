package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Список в редакторе (ADR-0049 §4): строки-пункты — куском «Список», перевод строки в заголовке,
 * ступени и «Без имени», пункт обратно строкой текста, «Удалить» и «Выше».
 */
class ComposedListsTest {

    /** Подделка разбора: `- `, `  - `, `    - ` — пункты, `# ` — заголовок, остальное — текст. */
    private val signs = object : TypedSource {
        private val kinds = listOf("    - " to LineKind.LIST3, "  - " to LineKind.LIST2, "- " to LineKind.LIST1, "# " to LineKind.HEADING1)
        override fun read(source: String): Typed {
            val lines = source.split('\n').map { l -> kinds.firstOrNull { l.startsWith(it.first) }?.let { l.removePrefix(it.first) to it.second } ?: (l to LineKind.PARAGRAPH) }
            return Typed(lines.joinToString("\n") { it.first }, lines.map { it.second })
        }
        override fun write(typed: Typed) = typed.text.split('\n').mapIndexed { i, l ->
            (kinds.firstOrNull { it.second == typed.lines.getOrNull(i) }?.first ?: "") + l
        }.joinToString("\n")
        override fun of(text: String) = emptyList<TypingSpan>()
    }

    private fun list(vararg items: ComposedListItem) = ComposedItem.ItemList(items.toList())

    @Test
    fun строки_пункты_становятся_списком() {
        val doc = ComposedDoc(listOf(ComposedItem.Text("до\n- раз\n  - два\nпосле")))
        val m = ComposedLists.lift(doc, signs, "Без имени", Spot(0), "до\nраз\nд".length)
        assertEquals(
            listOf(
                ComposedItem.Text("до"),
                list(ComposedListItem(1, "раз"), ComposedListItem(2, "два")),
                ComposedItem.Text("после"),
            ),
            m.doc.items,
        )
        assertEquals(Spot(1, 1, Spot.TITLE), m.spot, "курсор — в заголовке «два»")
        assertEquals(1, m.cursor)
    }

    @Test
    fun список_за_списком_сливается_и_ступень_без_родителя() {
        val doc = ComposedDoc(listOf(list(ComposedListItem(1, "a")), ComposedItem.Text("    - b")))
        val m = ComposedLists.lift(doc, signs, "Без имени", Spot(1), 1)
        val l = assertIs<ComposedItem.ItemList>(m.doc.items.single())
        assertEquals(listOf(1 to "a", 2 to "Без имени", 3 to "b"), l.items.map { it.depth to it.title })
        assertEquals(Spot(0, 2, Spot.TITLE), m.spot, "курсор — в «b», ниже вставленного")
        assertEquals(listOf(1, 2), ComposedLists.normalized(listOf(ComposedListItem(1), ComposedListItem(3)), "").map { it.depth }, "без заголовка — ступенью ниже соседа")
    }

    @Test
    fun перевод_строки_в_заголовке() {
        val doc = ComposedDoc(listOf(list(ComposedListItem(1, "ab", text = "под", widgets = listOf(ComposedWidget.Emoji("x"))))))
        val split = ComposedLists.enter(doc, Spot(0, 0, Spot.TITLE), signs.read("ab"), 1, signs)!!
        val l = assertIs<ComposedItem.ItemList>(split.doc.items.single())
        assertEquals(listOf("a", "b"), l.items.map { it.title })
        assertEquals("под", l.items[0].text, "содержимое — у прежнего пункта")
        assertEquals(Spot(0, 1, Spot.TITLE), split.spot)

        val nested = ComposedDoc(listOf(list(ComposedListItem(1, "a"), ComposedListItem(2, ""))))
        val up = ComposedLists.enter(nested, Spot(0, 1, Spot.TITLE), signs.read(""), 0, signs)!!
        assertEquals(listOf(1, 1), assertIs<ComposedItem.ItemList>(up.doc.items.single()).items.map { it.depth }, "пустой — ступенью выше")

        val last = ComposedDoc(listOf(list(ComposedListItem(1, "a"), ComposedListItem(1, ""), ComposedListItem(1, "c"))))
        val out = ComposedLists.enter(last, Spot(0, 1, Spot.TITLE), signs.read(""), 0, signs)!!
        assertEquals(3, out.doc.items.size, "список — до, текст, список — после")
        assertEquals(ComposedItem.Text(""), out.doc.items[1])
        assertEquals(Spot(1), out.spot)
    }

    @Test
    fun пункт_обратно_строкой_текста() {
        val doc = ComposedDoc(listOf(list(ComposedListItem(1, "a"), ComposedListItem(1, "b", "под", listOf(ComposedWidget.Emoji("x"))), ComposedListItem(2, "c"))))
        val m = ComposedLists.toText(doc, Spot(0, 1, Spot.TITLE), LineKind.HEADING1, signs)!!
        assertEquals(
            listOf(
                list(ComposedListItem(1, "a")),
                ComposedItem.Text("# b\nпод"),
                ComposedItem.Widget(ComposedWidget.Emoji("x")),
                list(ComposedListItem(1, "c")),
            ),
            m.doc.items,
        )
        assertEquals(Spot(1), m.spot)
    }

    @Test
    fun удалить_и_выше_в_списке() {
        val doc = ComposedDoc(listOf(list(ComposedListItem(1, "a"), ComposedListItem(1, "b", widgets = listOf(ComposedWidget.Emoji("x"), ComposedWidget.Emoji("y"))))))
        val (swapped, at) = ComposedLayout.shift(doc, Spot(0, 1, 1), -1)
        assertEquals(listOf(ComposedWidget.Emoji("y"), ComposedWidget.Emoji("x")), assertIs<ComposedItem.ItemList>(swapped.items[0]).items[1].widgets)
        assertEquals(Spot(0, 1, 0), at)
        val (up, _) = ComposedLayout.shift(doc, Spot(0, 1), -1)
        assertEquals(listOf("b", "a"), assertIs<ComposedItem.ItemList>(up.items[0]).items.map { it.title })
        val (fewer, _) = ComposedLayout.removeAt(doc, Spot(0, 0))
        assertEquals(listOf("b"), assertIs<ComposedItem.ItemList>(fewer.items[0]).items.map { it.title })
        val (gone, _) = ComposedLayout.removeAt(fewer, Spot(0, 0))
        assertTrue(gone.items.single() is ComposedItem.Text, "последний пункт — со списком")
    }

    @Test
    fun виджет_шире_места_пункта_не_помещается() {
        val wide = ComposedItem.ItemList(listOf(ComposedListItem(3, "a", widgets = listOf(ComposedWidget.Quoted("group", "g", 1)))))
        assertEquals(TableFit.CONTENT_DP - ListFit.STEP_DP * 4 < 140, ListFit.tooNarrow(wide))
        assertTrue(!ListFit.tooNarrow(wide.copy(flat = true)), "без отступа — помещается")
    }

}
