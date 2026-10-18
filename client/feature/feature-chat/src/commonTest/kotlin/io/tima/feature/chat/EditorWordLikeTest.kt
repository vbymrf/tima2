package io.tima.feature.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import io.tima.domain.chat.InlineKind
import io.tima.domain.chat.Spot
import io.tima.domain.chat.TypingMarks
import io.tima.domain.chat.TypingSpan
import io.tima.domain.chat.TypingStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Редактор «как в Word» (заказчик 2026-10-18): знаки разметки спрятаны, Ж без выделения ждёт буквы и
 * закрывается повторным нажатием, стирание не ломает знаки; нажатие под сообщением — писать после всего.
 */
class EditorWordLikeTest {

    /** Разбор-подделка: жирный `**…**` в строке и пункт `- `, как у быстрого оформления. */
    private val marks = TypingMarks { text ->
        val out = mutableListOf<TypingSpan>()
        var offset = 0
        for (line in text.split('\n')) {
            if (line.startsWith("- ")) out += TypingSpan(TypingStyle.MARKER, offset, offset + 2)
            Regex("""\*\*(.+?)\*\*""").findAll(line).forEach { m ->
                val a = offset + m.range.first
                val b = offset + m.range.last + 1
                out += TypingSpan(TypingStyle.MARKER, a, a + 2)
                out += TypingSpan(TypingStyle.BOLD, a + 2, b - 2)
                out += TypingSpan(TypingStyle.MARKER, b - 2, b)
            }
            offset += line.length + 1
        }
        out
    }

    private fun model(text: String, cursor: Int = text.length): EditorModel {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Text(text))))
        m.typing = marks
        m.changed(0, TextFieldValue(text, TextRange(cursor)))
        return m
    }

    /** Набрать [s] на месте курсора — как клавиатура: текст с буквой и курсор за ней. */
    private fun EditorModel.type(s: String) {
        val t = focusedText!!
        val c = field.selection.start
        changed(0, TextFieldValue(t.substring(0, c) + s + t.substring(c), TextRange(c + s.length)))
    }

    private fun EditorModel.backspace() {
        val t = focusedText!!
        val c = field.selection.start
        changed(0, TextFieldValue(t.removeRange(c - 1, c), TextRange(c - 1)))
    }

    @Test
    fun знаки_спрятаны_курсор_за_видимой_буквой() {
        val v = HiddenMarks.of("a **b** c", marks.of("a **b** c"))
        assertEquals("a b c", v.shown)
        assertEquals(3, v.toShown(5), "за «b» на экране")
        assertEquals(5, v.toSource(3), "за «b» в набранном — перед закрывающими знаками: набор продолжит жирный")
        assertEquals("• пункт", HiddenMarks.of("- пункт", marks.of("- пункт")).shown, "пункт — точкой")
        val start = HiddenMarks.of("**b**", marks.of("**b**"))
        assertEquals(2, start.toSource(0), "в начале строки — за её знаками")
    }

    @Test
    fun ж_без_выделения_ждёт_буквы_и_закрывается_повторным_нажатием() {
        val m = model("a")
        m.inline(InlineKind.BOLD)
        assertEquals("a", m.focusedText, "пары знаков у курсора нет")
        assertEquals(InlineKind.BOLD, m.pending)
        assertTrue(InlineKind.BOLD in m.activeInline(), "кнопка Ж нажата")
        m.type("b")
        assertEquals("a**b**", m.focusedText)
        assertEquals(4, m.field.selection.start, "курсор внутри жирного")
        m.type("c")
        assertEquals("a**bc**", m.focusedText)
        m.inline(InlineKind.BOLD)
        assertEquals(7, m.field.selection.start, "повторное Ж — курсор за знаки")
        assertNull(m.pending)
        m.type("d")
        assertEquals("a**bc**d", m.focusedText, "дальше — обычным")
    }

    @Test
    fun смена_вида_закрывает_прежний() {
        val m = model("**ab**", cursor = 4)
        m.inline(InlineKind.ITALIC)
        assertEquals(6, m.field.selection.start, "жирный закрыт")
        assertEquals(InlineKind.ITALIC, m.pending, "курсив — со следующей буквы")
        m.type("x")
        assertEquals("**ab***x*", m.focusedText)
    }

    @Test
    fun стёртый_спрятанный_знак_стирает_видимую_букву() {
        val m = model("a**bc**")
        m.backspace()
        assertEquals("a**b**", m.focusedText, "стёрта «c», знаки целы")
        assertEquals(4, m.field.selection.start)
        m.backspace()
        assertEquals("a", m.focusedText, "пустая пара убрана")
        assertEquals(InlineKind.BOLD, m.pending, "следующая буква — снова жирным")
    }

    @Test
    fun перевод_строки_в_конце_жирного_уходит_за_знаки() {
        val m = model("**ab**", cursor = 4)
        m.type("\n")
        assertEquals("**ab**\n", m.focusedText)
        assertEquals(InlineKind.BOLD, m.pending, "жирный продолжается на новой строке")
    }

    /** Выделенное оформляют кнопки ленты: нажата, если выделенное уже такое; повторное — снимает. */
    @Test
    fun выделенное_жирное_кнопка_нажата_и_снимает() {
        val m = model("a **bc** d")
        // Выделили «bc» на экране — поле отдаёт места в набранном, со спрятанными знаками по краю.
        val v = HiddenMarks.of("a **bc** d", marks.of("a **bc** d"))
        m.changed(0, TextFieldValue("a **bc** d", TextRange(v.toSource(2), v.toSource(4))))
        assertTrue(InlineKind.BOLD in m.activeInline())
        m.inline(InlineKind.BOLD)
        assertEquals("a bc d", m.focusedText, "знаки сняты, второй пары нет")
        m.inline(InlineKind.BOLD)
        assertEquals("a **bc** d", m.focusedText)
    }

    @Test
    fun нажатие_под_виджетом_даёт_писать_после_всего() {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Text("x"), ComposedItem.Widget(ComposedWidget.Emoji("🎉")))))
        m.writeAtEnd()
        assertEquals(3, m.doc.items.size)
        assertEquals(ComposedItem.Text(""), m.doc.items.last())
        assertEquals(Spot(2), m.spot)
        assertEquals(1, m.focusAsk, "окно поставит туда курсор")
    }

    @Test
    fun нажатие_под_текстом_как_enter_в_конце() {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Text("x"))))
        m.writeAtEnd()
        assertEquals("x\n", m.focusedText)
        assertEquals(2, m.field.selection.start)
        m.writeAtEnd()
        assertEquals("x\n", m.focusedText, "второе нажатие пустых строк не множит")
    }

    @Test
    fun без_знаков_md_прежняя_пара_у_курсора() {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Text("a"))))
        m.changed(0, TextFieldValue("a", TextRange(1)))
        m.inline(InlineKind.BOLD)
        assertEquals("a****", m.focusedText)
    }
}
