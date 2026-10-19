package io.tima.feature.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.LineKind
import io.tima.domain.chat.ParagraphKind
import io.tima.domain.chat.Spot
import io.tima.domain.chat.TypedSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Поле редактора без знаков (ADR-0048): курсор и стирание — по видимым буквам; Ж без выделения ждёт
 * буквы; «Знаки md» превращают набранную пару в оформление; в документе — набранное со знаками.
 */
class EditorTypedTest {

    private fun model(source: String, cursor: Int? = null): EditorModel {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Text(source))), MarkdownTyped)
        val t = m.focusedTyped!!.text
        m.changed(0, TextFieldValue(t, TextRange(cursor ?: t.length)))
        return m
    }

    /** Набрать [s] по букве на месте курсора — как клавиатура. */
    private fun EditorModel.type(s: String) {
        for (ch in s) {
            val t = field.text
            val c = field.selection.start
            changed(0, TextFieldValue(t.substring(0, c) + ch + t.substring(c), TextRange(c + 1)))
        }
    }

    private fun EditorModel.backspace() {
        val t = field.text
        val c = field.selection.start
        changed(0, TextFieldValue(t.removeRange(c - 1, c), TextRange(c - 1)))
    }

    @Test
    fun в_поле_нет_знаков_стирание_по_видимым_буквам() {
        val m = model("a **bc** d", cursor = 4)
        assertEquals("a bc d", m.field.text)
        m.backspace()
        assertEquals("a b d", m.field.text)
        assertEquals("a **b** d", m.focusedText, "знаки целы, стёрта буква")
        m.backspace()
        assertEquals("a  d", m.focusedText, "слово стёрто — и оформления нет")
    }

    @Test
    fun набранная_пара_становится_жирным_знаки_выключены_остаются_текстом() {
        val m = model("")
        m.type("**жир**")
        assertEquals("жир", m.field.text)
        assertEquals(3, m.field.selection.start)
        assertEquals("**жир**", m.focusedText)
        val off = model("")
        off.toggleMarks()
        off.type("**жир**")
        assertEquals("**жир**", off.field.text)
        assertTrue(off.focusedTyped!!.spans.isEmpty())
    }

    @Test
    fun знак_строки_даёт_заголовок() {
        val m = model("")
        m.type("# Сбор")
        assertEquals("Сбор", m.field.text)
        assertEquals(listOf(LineKind.HEADING1), m.focusedTyped!!.lines)
        assertEquals("# Сбор", m.focusedText)
        assertEquals(ParagraphKind.HEADING1, m.paragraphHere)
    }

    @Test
    fun ж_без_выделения_ждёт_буквы_и_закрывается() {
        val m = model("a")
        m.inline(InlineKind.BOLD)
        assertEquals("a", m.field.text, "пары знаков у курсора нет")
        assertEquals(setOf(InlineKind.BOLD), m.pending)
        assertTrue(InlineKind.BOLD in m.activeInline(), "кнопка Ж нажата")
        m.type("bc")
        assertEquals("a**bc**", m.focusedText)
        assertNull(m.pending)
        m.inline(InlineKind.BOLD)
        m.type("d")
        assertEquals("a**bc**d", m.focusedText, "дальше — обычным")
    }

    @Test
    fun выделение_кнопкой_и_абзац() {
        val m = model("сбор в")
        m.changed(0, TextFieldValue("сбор в", TextRange(0, 4)))
        m.inline(InlineKind.BOLD)
        assertEquals("**сбор** в", m.focusedText)
        assertEquals(TextRange(0, 4), m.field.selection, "выделение остаётся на слове")
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 0, 4)), m.focusedTyped!!.spans)
        m.inline(InlineKind.BOLD)
        assertEquals("сбор в", m.focusedText, "второе нажатие снимает")
        m.paragraph(ParagraphKind.QUOTE)
        assertEquals("--> сбор в", m.focusedText)
    }

    @Test
    fun простой_текст_из_буфера_знаками_не_оформляется() {
        val m = model("")
        m.paste("**x**", plain = true)
        assertEquals("**x**", m.field.text)
        assertTrue(m.focusedTyped!!.spans.isEmpty())
        val styled = model("")
        styled.paste("**x**")
        assertEquals("x", styled.field.text, "«С оформл.» — оформление переносится")
        assertEquals("**x**", styled.focusedText)
    }

    @Test
    fun нажатие_под_виджетом_даёт_писать_после_всего() {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Text("x"), ComposedItem.Widget(ComposedWidget.Emoji("🎉")))), MarkdownTyped)
        m.writeAtEnd()
        assertEquals(3, m.doc.items.size)
        assertEquals(ComposedItem.Text(""), m.doc.items.last())
        assertEquals(Spot(2), m.spot)
        assertEquals(1, m.focusAsk, "окно поставит туда курсор")
    }

    @Test
    fun нажатие_под_текстом_как_enter_в_конце() {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Text("# x"))), MarkdownTyped)
        m.writeAtEnd()
        assertEquals("# x\n", m.focusedText)
        assertEquals(2, m.field.selection.start)
        m.writeAtEnd()
        assertEquals("# x\n", m.focusedText, "второе нажатие пустых строк не множит")
    }

    @Test
    fun слияние_кнопкой_в_ряду() {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Row(listOf(io.tima.domain.chat.ComposedCell(50, "a"), io.tima.domain.chat.ComposedCell(50, "b"))))))
        m.select(Spot(0, 1))
        assertTrue(m.join("left"))
        val row = m.doc.items[0] as ComposedItem.Row
        assertEquals(listOf("", "left"), row.cells.map { it.merge })
        assertEquals("a\nb", row.cells[0].text)
        m.select(Spot(0, 0))
        assertTrue(!m.join("left"), "в первом столбце слева не с чем")
        m.undo()
        assertEquals(listOf("", ""), (m.doc.items[0] as ComposedItem.Row).cells.map { it.merge }, "слияние отменяется")
    }
}
