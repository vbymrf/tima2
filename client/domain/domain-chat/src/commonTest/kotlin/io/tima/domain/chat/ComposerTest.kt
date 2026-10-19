package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Редактор (Решения §10): история шагов, чип собранного, отправка. Правка текста — [TypedEditsTest]. */
class ComposerTest {

    @Test
    fun история_на_50_шагов() {
        val h = EditHistory(0)
        for (i in 1..60) h.push(i)
        repeat(60) { h.undo() }
        assertEquals(10, h.current, "назад — не дальше 50 шагов")
        assertFalse(h.canUndo)
        h.redo()
        assertEquals(11, h.current)
        h.push(99)
        assertFalse(h.canRedo, "новый шаг стирает «повторить»")
        h.push(99)
        h.undo()
        assertEquals(11, h.current, "то же самое — не шаг")
    }

    @Test
    fun чип_знает_первую_строку_и_число_рядов() {
        val doc = ComposedDoc(
            listOf(
                ComposedItem.Text("\n  # Сбор\nв 9"),
                ComposedItem.Widget(ComposedWidget.Emoji("🎉")),
                ComposedItem.Text(" "),
            ),
        )
        assertEquals("# Сбор", doc.firstLine)
        assertEquals(2, doc.rows)
        assertTrue(ComposedDoc().isEmpty)
    }

    @Test
    fun отправка_собранного() {
        val queued = mutableListOf<Int>()
        val bodies = object : ComposedBodies {
            override fun problems(doc: ComposedDoc) = if (doc.items.any { it is ComposedItem.Row }) listOf("плохо") else emptyList()
            override fun encode(doc: ComposedDoc, reply: ReplyTarget?) = if (problems(doc).isEmpty()) ByteArray(1) else null
            override fun preview(doc: ComposedDoc): RichContent? = null
            override fun save(doc: ComposedDoc) = ""
            override fun load(saved: String): ComposedDoc? = null
        }
        val send = SendComposed({ _, _, _, level, _, _ -> queued += level; true }, bodies, { "k" })
        assertEquals(ComposedResult.Empty, send.send("c", ComposedDoc(), -1))
        assertIs<ComposedResult.Invalid>(send.send("c", ComposedDoc(listOf(ComposedItem.Row(listOf(ComposedCell(100, "x"))))), -1))
        assertIs<ComposedResult.Queued>(send.send("c", ComposedDoc.of("x").copy(level = 3), 2))
        assertIs<ComposedResult.Queued>(send.send("c", ComposedDoc.of("x"), 2))
        assertEquals(listOf(3, 2), queued, "уровень редактора важнее уровня переписки")
        val wide = "| " + (1..9).joinToString(" | ") + " |\n|" + List(9) { "---" }.joinToString("|") + "|"
        assertEquals(ComposedResult.TableTooWide(7), send.send("c", ComposedDoc.of(wide), 2), "«99» не помещается — не отправляется")
        assertEquals(2, queued.size)
    }
}
