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

    /** Набрать [s] по букве там, где курсор: кусок-текст, ячейка или пункт списка. */
    private fun EditorModel.typeHere(s: String) {
        for (ch in s) {
            val t = field.text
            val c = field.selection.start
            val v = TextFieldValue(t.substring(0, c) + ch + t.substring(c), TextRange(c + 1))
            val at = spot
            when {
                doc.items[at.item] is ComposedItem.ItemList -> changedItem(at.item, at.cell, at.widget, v)
                at.cell >= 0 -> changedCell(at.item, at.cell, v)
                else -> changed(at.item, v)
            }
        }
    }

    /** ADR-0049 §4: «- » — пункт; перевод строки — новый пункт; в пустом — конец списка. */
    @Test
    fun список_набором_и_кнопками() {
        val m = model("")
        m.untitled = "Без имени"
        m.typeHere("- раз")
        var list = m.doc.items.first() as ComposedItem.ItemList
        assertEquals(listOf("раз"), list.items.map { it.title })
        assertEquals(Spot(0, 0, Spot.TITLE), m.spot)
        m.typeHere("\nдва")
        list = m.doc.items.first() as ComposedItem.ItemList
        assertEquals(listOf("раз", "два"), list.items.map { it.title })
        m.typeHere("\n\n")
        assertEquals(ComposedItem.Text(""), m.doc.items.last(), "пустой пункт — список кончился, ниже текст")
        assertEquals(Spot(1), m.spot)

        m.select(Spot(0, 0))
        m.insert(ComposedItem.Widget(ComposedWidget.Emoji("🎒")))
        assertEquals(listOf<ComposedWidget>(ComposedWidget.Emoji("🎒")), (m.doc.items[0] as ComposedItem.ItemList).items[0].widgets, "виджет — в пункт")

        m.focusedItem(0, 1, Spot.TITLE)
        m.paragraph(ParagraphKind.LIST2)
        assertEquals(listOf(1, 2), (m.doc.items[0] as ComposedItem.ItemList).items.map { it.depth })
        assertEquals(ParagraphKind.LIST2, m.paragraphHere)
        m.paragraph(ParagraphKind.HEADING1)
        assertEquals("# два", (m.doc.items[1] as ComposedItem.Text).source, "пункт — строкой заголовка")
    }

    /** ADR-0049 §5: одна кнопка «Код» — в строке код в строке, целые строки и пустая строка — блок кода. */
    @Test
    fun одна_кнопка_код() {
        val m = model("a bc d\nвторая")
        m.changed(0, TextFieldValue(m.field.text, TextRange(2, 4)))
        m.code()
        assertEquals(listOf(TypedSpan(InlineKind.CODE, 2, 4)), m.focusedTyped!!.spans, "внутри строки — код в строке")
        m.changed(0, TextFieldValue(m.field.text, TextRange(7, 13)))
        m.code()
        assertEquals(LineKind.CODE, m.focusedTyped!!.lines[1], "целая строка — блок кода")
        assertTrue(InlineKind.CODE in m.activeInline())
        m.changed(0, TextFieldValue(m.field.text, TextRange(9)))
        m.code()
        assertEquals(LineKind.PARAGRAPH, m.focusedTyped!!.lines[1], "в блоке — снят")
        val empty = model("")
        empty.code()
        assertEquals(listOf(LineKind.CODE), empty.focusedTyped!!.lines, "пустая строка — блок кода")
        assertTrue(ParagraphKind.CODE !in listOf(m.paragraphHere), "отметка «Абзаца» — не код")
    }

    /** ADR-0049 §3: «Развернуть по умолчанию» и «Без отступа» — на весь список, шагом истории. */
    @Test
    fun свойства_списка() {
        val m = model("")
        m.typeHere("- a")
        m.listProps(open = false, flat = true)
        val list = m.doc.items.first() as ComposedItem.ItemList
        assertTrue(!list.open && list.flat)
        m.undo()
        assertTrue((m.doc.items.first() as ComposedItem.ItemList).open, "отменяется")
        m.writeAtEnd()
        assertNull(m.listHere, "курсор вне списка — свойств нет")
    }

    /** ADR-0050 §3: дописанный адрес — блок-кнопка на его месте; в коде — текст; окну — адрес для карточки. */
    @Test
    fun адрес_в_тексте_становится_блоком() {
        val m = model("")
        var asked: String? = null
        m.onLink = { asked = it }
        m.typeHere("см tima.ru дальше")
        assertEquals(
            listOf(ComposedItem.Text("см "), ComposedItem.Widget(ComposedWidget.Link("https://tima.ru", mode = "button")), ComposedItem.Text("дальше")),
            m.doc.items,
        )
        assertEquals("https://tima.ru", asked)
        assertEquals(Spot(2), m.spot, "писать дальше — после ссылки")
        m.replaceLink("https://tima.ru", ComposedWidget.Link("https://tima.ru", "ТИМА", "card"))
        assertEquals(ComposedWidget.Link("https://tima.ru", "ТИМА", "card"), (m.doc.items[1] as ComposedItem.Widget).widget, "кнопка стала карточкой")

        val code = model("")
        code.typeHere("`tima.ru` ")
        assertTrue(code.doc.items.single() is ComposedItem.Text, "в коде адрес — текст")
    }

    /** Enter в заголовке — кнопкой действия клавиатуры (2026-10-20): второй пункт, курсор в нём. */
    @Test
    fun enter_кнопкой_даёт_второй_пункт() {
        val m = model("")
        m.typeHere("- раз")
        m.enterItem(0, 0)
        assertEquals(listOf("раз", ""), (m.doc.items[0] as ComposedItem.ItemList).items.map { it.title })
        assertEquals(Spot(0, 1, Spot.TITLE), m.spot)
        m.typeHere("два")
        m.enterItem(0, 1)
        m.typeHere("три")
        assertEquals(listOf("раз", "два", "три"), (m.doc.items[0] as ComposedItem.ItemList).items.map { it.title })
    }

    /** Вид строки сохраняется (заказчик 2026-10-19): заголовком набирали — новая ячейка начинается заголовком. */
    @Test
    fun вид_строки_переходит_в_новую_ячейку() {
        val m = EditorModel(ComposedDoc(listOf(ComposedItem.Row(listOf(io.tima.domain.chat.ComposedCell(50), io.tima.domain.chat.ComposedCell(50))))), MarkdownTyped)
        m.focusedCell(0, 0)
        m.paragraph(ParagraphKind.HEADING1)
        m.typeHere("А")
        m.focusedCell(0, 1)
        m.typeHere("Б")
        assertEquals("# Б", (m.doc.items[0] as ComposedItem.Row).cells[1].text)
    }

    /** Ступень 2 без 1 (заказчик 2026-10-19): над ней пункт «Без имени», правится как любой. */
    @Test
    fun ступень_без_родителя_получает_без_имени() {
        val m = model("")
        m.untitled = "Без имени"
        m.typeHere("  - вложенный")
        val list = m.doc.items.first() as ComposedItem.ItemList
        assertEquals(listOf(1 to "Без имени", 2 to "вложенный"), list.items.map { it.depth to it.title })
        assertEquals(Spot(0, 1, Spot.TITLE), m.spot)
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
