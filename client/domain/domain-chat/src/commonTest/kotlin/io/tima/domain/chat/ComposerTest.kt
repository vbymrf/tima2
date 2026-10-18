package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Редактор (Решения §10): кнопки ставят и снимают знаки так, как набрал бы человек. */
class ComposerTest {

    @Test
    fun абзац_меняет_знак_строки_под_курсором() {
        val src = "первая\nвторая\nтретья"
        val h = ComposedText.paragraph(src, 9, ParagraphKind.HEADING2)
        assertEquals("первая\n## вторая\nтретья", h.source)
        assertEquals(12, h.start, "курсор остаётся на той же букве")
        assertEquals(ParagraphKind.HEADING2, ComposedText.paragraphAt(h.source, h.start))
        val q = ComposedText.paragraph(h.source, h.start, ParagraphKind.QUOTE)
        assertEquals("первая\n--> вторая\nтретья", q.source)
        val p = ComposedText.paragraph(q.source, q.start, ParagraphKind.PARAGRAPH)
        assertEquals(src, p.source)
        assertEquals(ParagraphKind.LIST2, ComposedText.paragraphAt(ComposedText.paragraph(src, 0, ParagraphKind.LIST2).source, 0))
    }

    @Test
    fun код_оборачивает_строку_и_снимается_абзацем() {
        val code = ComposedText.paragraph("a\nfun x()\nb", 4, ParagraphKind.CODE)
        assertEquals("a\n```\nfun x()\n```\nb", code.source)
        val back = ComposedText.paragraph(code.source, code.start, ParagraphKind.PARAGRAPH)
        assertEquals("a\nfun x()\nb", back.source)
    }

    @Test
    fun оформление_выделенного_ставится_и_снимается() {
        val src = "сбор в 9:00 у входа"
        val bold = ComposedText.inline(src, 7, 11, InlineKind.BOLD)
        assertEquals("сбор в **9:00** у входа", bold.source)
        assertEquals("9:00", bold.source.substring(bold.start, bold.end))
        assertEquals(src, ComposedText.inline(bold.source, bold.start, bold.end, InlineKind.BOLD).source, "знаки вокруг — снять")
        assertEquals(src, ComposedText.inline(bold.source, 7, 15, InlineKind.BOLD).source, "знаки внутри — снять")
        assertEquals("сбор в ***9:00*** у входа", ComposedText.inline(bold.source, bold.start, bold.end, InlineKind.ITALIC).source,
            "курсив поверх жирного — не снятие жирного")
    }

    @Test
    fun пробелы_по_краям_выделения_остаются_снаружи() {
        assertEquals("a ==b== c", ComposedText.inline("a b c", 1, 4, InlineKind.HIGHLIGHT).source)
        val empty = ComposedText.inline("ab", 1, 1, InlineKind.STRIKE)
        assertEquals("a~~~~b", empty.source)
        assertEquals(3, empty.start)
    }

    @Test
    fun знак_разметки_кнопкой_экранируется() {
        assertEquals("a\\*b", ComposedText.symbol("ab", 1, 1, "*", marks = true).source)
        assertEquals("\\# x", ComposedText.symbol(" x", 0, 0, "#", marks = true).source)
        assertEquals("a#", ComposedText.symbol("a", 1, 1, "#", marks = true).source, "внутри строки «#» ничего не значит")
        assertEquals("a*", ComposedText.symbol("a", 1, 1, "*", marks = false).source, "знаки выключены — экранировать незачем")
        assertEquals("a b", ComposedText.symbol("ab", 1, 1, " ", marks = true).source)
    }

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
    }
}
