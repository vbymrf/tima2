package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правка набранного без знаков (ADR-0048): отрезки и виды строк идут за правкой; «Знаки md»
 * превращают набранную пару и знак строки в оформление сразу.
 */
class TypedEditsTest {

    private fun typed(text: String, vararg spans: TypedSpan) = TypedEdits.normalized(Typed.plain(text).copy(spans = spans.toList()))

    /** Набрать [s] на месте [at] — как поле: новый текст и курсор за набранным. */
    private fun type(t: Typed, at: Int, s: String, pending: Set<InlineKind>? = null, convert: Boolean = true): TypedEdits.Change =
        TypedEdits.change(t, t.text.substring(0, at) + s + t.text.substring(at), at + s.length, pending, convert)

    private fun typeAll(t: Typed, s: String, convert: Boolean = true): TypedEdits.Change {
        var c = TypedEdits.Change(t, t.text.length)
        for (ch in s) {
            val at = c.cursor ?: c.typed.text.length
            c = type(c.typed, at, ch.toString(), convert = convert).let { if (it.cursor == null) it.copy(cursor = at + 1) else it }
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
    fun набранная_пара_сразу_становится_оформлением() {
        val c = typeAll(Typed.plain("это "), "**жир**")
        assertEquals("это жир", c.typed.text)
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 4, 7)), c.typed.spans)
        assertEquals(7, c.cursor)
        for ((sign, kind) in listOf("*" to InlineKind.ITALIC, "++" to InlineKind.UNDERLINE, "~~" to InlineKind.STRIKE, "==" to InlineKind.HIGHLIGHT, "`" to InlineKind.CODE)) {
            val k = typeAll(Typed.plain(""), "${sign}сл$sign")
            assertEquals("сл", k.typed.text, sign)
            assertEquals(listOf(TypedSpan(kind, 0, 2)), k.typed.spans, sign)
        }
    }

    @Test
    fun знаки_выключены_остаются_текстом() {
        val c = typeAll(Typed.plain(""), "**жир** # x", convert = false)
        assertEquals("**жир** # x", c.typed.text)
        assertTrue(c.typed.spans.isEmpty())
    }

    @Test
    fun незакрытая_пара_и_звёздочка_в_жирном_не_курсив() {
        val open = typeAll(Typed.plain(""), "**жир*")
        assertEquals("**жир*", open.typed.text, "одна закрывающая звёздочка из двух — ещё не пара")
        assertTrue(open.typed.spans.isEmpty())
        val spaced = typeAll(Typed.plain(""), "a ** b **")
        assertTrue(spaced.typed.spans.isEmpty(), "у пробела знак не работает")
    }

    @Test
    fun знак_строки_в_её_начале_становится_видом() {
        for ((sign, kind) in listOf("# " to LineKind.HEADING1, "## " to LineKind.HEADING2, "### " to LineKind.HEADING3, "- " to LineKind.LIST1, "--> " to LineKind.QUOTE)) {
            val c = typeAll(Typed.plain(""), sign)
            assertEquals("", c.typed.text, sign)
            assertEquals(listOf(kind), c.typed.lines, sign)
        }
        val code = typeAll(Typed.plain(""), "```")
        assertEquals(listOf(LineKind.CODE), code.typed.lines)
        val divider = typeAll(Typed.plain("a\n"), "---")
        assertEquals("a\n\n", divider.typed.text)
        assertEquals(listOf(LineKind.PARAGRAPH, LineKind.DIVIDER, LineKind.PARAGRAPH), divider.typed.lines)
        assertEquals(3, divider.cursor, "писать — под разделителем")
        // Где вида строки нет — знак строки остаётся текстом.
        val cell = TypedEdits.change(Typed.plain("#"), "# ", 2, null, convert = true, lineSigns = false)
        assertEquals("# ", cell.typed.text)
    }

    @Test
    fun перевод_строки_продолжает_список_и_кончает_заголовок() {
        val list = Typed("пункт", listOf(LineKind.LIST1))
        val next = type(list, 5, "\n", convert = false).typed
        assertEquals(listOf(LineKind.LIST1, LineKind.LIST1), next.lines)
        val ended = type(next, 6, "\n", convert = false)
        assertEquals("пункт\n", ended.typed.text, "пустой пункт — список кончился, перевода нет")
        assertEquals(listOf(LineKind.LIST1, LineKind.PARAGRAPH), ended.typed.lines)
        val heading = Typed("Заг", listOf(LineKind.HEADING1))
        assertEquals(listOf(LineKind.HEADING1, LineKind.PARAGRAPH), type(heading, 3, "\n").typed.lines)
        assertEquals(listOf(LineKind.PARAGRAPH, LineKind.HEADING1), type(heading, 0, "\n").typed.lines, "в начале — заголовок уходит вниз")
    }

    @Test
    fun стёртый_перевод_перед_пунктом_снимает_вид() {
        val t = Typed("a\nпункт", listOf(LineKind.PARAGRAPH, LineKind.LIST1))
        val c = TypedEdits.change(t, "aпункт", 1, null, convert = true)
        assertEquals("a\nпункт", c.typed.text)
        assertEquals(listOf(LineKind.PARAGRAPH, LineKind.PARAGRAPH), c.typed.lines)
        assertEquals(2, c.cursor)
    }

    @Test
    fun стёртое_слово_уносит_отрезок_замена_сохраняет() {
        val t = typed("a жир b", TypedSpan(InlineKind.BOLD, 2, 5))
        assertTrue(TypedEdits.change(t, "a  b", 2, null, convert = true).typed.spans.isEmpty())
        // Подсказка клавиатуры заменила слово целиком — оформление остаётся.
        assertEquals(listOf(TypedSpan(InlineKind.BOLD, 2, 6)), TypedEdits.change(t, "a жиры b", 6, null, convert = true).typed.spans)
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
        assertNull(TypedEdits.change(Typed.plain(""), "x", 1, null, convert = true).cursor)
    }
}
