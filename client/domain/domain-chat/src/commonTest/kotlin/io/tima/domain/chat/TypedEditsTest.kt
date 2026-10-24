package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правка набранного в редакторе: отрезки и виды строк идут за правкой; набранный знак md — текст
 * (ADR-0053: знаки разбирает только строка ввода, при отправке).
 */
class TypedEditsTest {

    private fun typed(text: String, vararg spans: TypedSpan) = TypedEdits.normalized(Typed.plain(text).copy(spans = spans.toList()))

    /** Набрать [s] на месте [at] — как поле: новый текст и курсор за набранным. */
    private fun type(t: Typed, at: Int, s: String, pending: Set<InlineKind>? = null): TypedEdits.Change =
        TypedEdits.change(t, t.text.substring(0, at) + s + t.text.substring(at), at + s.length, pending)

    private fun typeAll(t: Typed, s: String): TypedEdits.Change {
        var c = TypedEdits.Change(t, t.text.length)
        for (ch in s) {
            val at = c.cursor ?: c.typed.text.length
            c = type(c.typed, at, ch.toString()).let { if (it.cursor == null) it.copy(cursor = at + 1) else it }
        }
        return c
    }

    @Test
    fun набор_в_конце_жирного_продолжает_его_в_начале_нет() {
        val t = typed("жир дальше", TypedSpan(InlineKind.BOLD, 0, 3))
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 0, 4)), type(t, 3, "н").typed.spans)
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 1, 4)), type(t, 0, "о").typed.spans)
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 0, 4)), type(t, 1, "ы").typed.spans)
        // Одинаковые буквы рядом: место решает курсор, а не общая часть строк.
        val aa = typed("аа", TypedSpan(InlineKind.BOLD, 0, 1))
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 0, 2)), type(aa, 1, "а").typed.spans)
    }

    @Test
    fun ж_без_выделения_ждёт_буквы_и_закрывает_жирный() {
        val t = typed("a")
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 1, 2)), type(t, 1, "b", pending = setOf(InlineKind.BOLD)).typed.spans)
        val bold = typed("ab", TypedSpan(InlineKind.BOLD, 0, 2))
        assertTrue(type(bold, 2, "c", pending = emptySet()).typed.spans == listOf(TypedSpan(InlineKind.BOLD, 0, 2)), "дальше — обычным")
    }

    @Test
    fun знаки_md_в_редакторе_остаются_текстом() {
        val c = typeAll(Typed.plain(""), "**жир** # x")
        assertEquals("**жир** # x", c.typed.text)
        assertTrue(c.typed.spans.isEmpty())
        val line = typeAll(Typed.plain(""), "# ")
        assertEquals("# ", line.typed.text)
        assertEquals(listOf(LineKind.PARAGRAPH), line.typed.lines, "знак строки — не вид строки")
    }

    @Test
    fun перевод_строки_продолжает_список_и_заголовок() {
        val list = Typed("пункт", listOf(LineKind.LIST1))
        val next = type(list, 5, "\n").typed
        assertEquals(listOf(LineKind.LIST1, LineKind.LIST1), next.lines)
        val ended = type(next, 6, "\n")
        assertEquals("пункт\n", ended.typed.text, "пустой пункт — список кончился, перевода нет")
        assertEquals(listOf(LineKind.LIST1, LineKind.PARAGRAPH), ended.typed.lines)
        val heading = Typed("Заг", listOf(LineKind.HEADING1))
        // Вид сохраняется на новой строке (заказчик 2026-10-19: «Закрепляем тип Абзац»).
        assertEquals(listOf(LineKind.HEADING1, LineKind.HEADING1), type(heading, 3, "\n").typed.lines)
        assertEquals(listOf(LineKind.HEADING1, LineKind.HEADING1), type(heading, 0, "\n").typed.lines, "в начале — тоже")
    }

    @Test
    fun стёртый_перевод_перед_пунктом_снимает_вид() {
        val t = Typed("a\nпункт", listOf(LineKind.PARAGRAPH, LineKind.LIST1))
        val c = TypedEdits.change(t, "aпункт", 1, null)
        assertEquals("a\nпункт", c.typed.text)
        assertEquals(listOf(LineKind.PARAGRAPH, LineKind.PARAGRAPH), c.typed.lines)
        assertEquals(2, c.cursor)
    }

    @Test
    fun стёртое_слово_уносит_отрезок_замена_сохраняет() {
        val t = typed("a жир b", TypedSpan(InlineKind.BOLD, 2, 5))
        assertTrue(TypedEdits.change(t, "a  b", 2, null).typed.spans.isEmpty())
        // Подсказка клавиатуры заменила слово целиком — оформление остаётся.
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 2, 6)), TypedEdits.change(t, "a жиры b", 6, null).typed.spans)
    }

    @Test
    fun кнопки_выделение_абзац_вставка() {
        val t = Typed.plain("a bc d")
        val bold = TypedEdits.toggle(t, 2, 4, InlineKind.BOLD)
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 2, 4)), bold.spans)
        assertTrue(TypedEdits.toggle(bold, 2, 4, InlineKind.BOLD).spans.isEmpty(), "второе нажатие снимает")
        val lines = TypedEdits.lines(Typed.plain("a\nb\nc"), 0, 2, LineKind.QUOTE)
        assertEquals(listOf(LineKind.QUOTE, LineKind.QUOTE, LineKind.PARAGRAPH), lines.lines)
        val inserted = TypedEdits.insert(bold, 6, 6, Typed("**x**\ny", listOf(LineKind.PARAGRAPH, LineKind.LIST1)))
        assertEquals("a bc d**x**\ny", inserted.typed.text, "знаки во вставке — текст")
        assertEquals(listOf(LineKind.PARAGRAPH, LineKind.LIST1), inserted.typed.lines)
        assertEquals(13, inserted.cursor)
    }

    @Test
    fun отрезок_не_идёт_через_перевод_строки_и_в_код() {
        val t = TypedEdits.normalized(Typed("ab\ncd", listOf(LineKind.PARAGRAPH, LineKind.CODE), listOf(TypedSpan(InlineKind.BOLD, 0, 5))))
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 0, 2)), t.spans)
        assertNull(TypedEdits.change(Typed.plain(""), "x", 1, null).cursor)
    }
}
