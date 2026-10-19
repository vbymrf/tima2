package io.tima.crypto

import io.tima.crypto.TypedLines.Kind
import io.tima.crypto.TypedLines.Line
import io.tima.crypto.TypedLines.Span
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Набранное без знаков (ADR-0048 §1): строки и отрезки ↔ текст со знаками. Запись читается обратно
 * теми же строками и даёт то же сообщение; знак, набранный текстом, знаком не становится.
 */
class TypedLinesTest {

    private fun roundTrip(lines: List<Line>): String {
        val written = TypedLines.write(lines)
        assertEquals(lines, TypedLines.read(written), "обратно другие строки: $written")
        return written
    }

    @Test
    fun все_виды_строк_туда_и_обратно() {
        val source = "# Заголовок\nабзац **жирный** *курсив* ++ч++ ~~з~~ ==м== `код`\n--> цитата\n- пункт\n  - вложенный\n" +
            "```\nкод **не жирный**\n```\n---\nконец"
        val lines = TypedLines.read(source)
        assertEquals(
            listOf(Kind.HEADING1, Kind.PARAGRAPH, Kind.QUOTE, Kind.LIST1, Kind.LIST2, Kind.CODE, Kind.DIVIDER, Kind.PARAGRAPH),
            lines.map { it.kind },
        )
        assertEquals("абзац жирный курсив ч з м код", lines[1].text)
        assertEquals("код **не жирный**", lines[5].text, "в коде знаки — текст")
        val written = roundTrip(lines)
        assertEquals(QuickMarkdown.parse(source), QuickMarkdown.parse(written), "то же сообщение")
    }

    @Test
    fun знаки_текстом_остаются_текстом() {
        val lines = listOf(
            Line(Kind.PARAGRAPH, "# не заголовок"),
            Line(Kind.PARAGRAPH, "- не пункт"),
            Line(Kind.PARAGRAPH, "--> не цитата"),
            Line(Kind.PARAGRAPH, "---"),
            Line(Kind.PARAGRAPH, "a ** b *c* ++d++ `e` \\ f"),
        )
        val written = roundTrip(lines)
        val content = QuickMarkdown.parse(written)
        assertFalse(content.hasMarkup, "оформления нет: $written")
        assertEquals(lines.joinToString("\n") { it.text }, content.plainText())
    }

    @Test
    fun адрес_без_косых() {
        val lines = listOf(Line(Kind.PARAGRAPH, "см https://ya.ru/?q=a+b*c==d и **ещё**"))
        val written = roundTrip(lines)
        assertTrue("https://ya.ru/?q=a+b*c==d" in written, "адрес как есть: $written")
        assertEquals(lines[0].text, QuickMarkdown.parse(written).plainText())
    }

    @Test
    fun жирный_курсив_на_одном_слове() {
        for (text in listOf("жк", "жк.", "(жк)")) {
            val a = if (text.startsWith("(")) 1 else 0
            val lines = listOf(Line(Kind.PARAGRAPH, text, listOf(Span(EntityType.BOLD, a, a + 2), Span(EntityType.ITALIC, a, a + 2))))
            val written = roundTrip(lines)
            val types = QuickMarkdown.parse(written).markup!!.entities.map { it.type }.toSet()
            assertEquals(setOf(EntityType.BOLD, EntityType.ITALIC), types, "оба вида: $written")
        }
    }

    @Test
    fun края_отрезка_без_пробелов_и_пересечение_обрезано() {
        val spaced = TypedLines.read(TypedLines.write(listOf(Line(Kind.PARAGRAPH, "жир дальше", listOf(Span(EntityType.BOLD, 0, 4))))))
        assertEquals(listOf(Span(EntityType.BOLD, 0, 3)), spaced.single().spans)
        val crossing = Line(Kind.PARAGRAPH, "abcdefg", listOf(Span(EntityType.BOLD, 0, 4), Span(EntityType.ITALIC, 2, 6)))
        val back = TypedLines.read(TypedLines.write(listOf(crossing))).single()
        assertEquals("abcdefg", back.text)
        assertEquals(setOf(Span(EntityType.BOLD, 0, 4), Span(EntityType.ITALIC, 2, 4)), back.spans.toSet())
    }

    @Test
    fun код_с_апострофом_внутри_остаётся_текстом() {
        val line = Line(Kind.PARAGRAPH, "a`b", listOf(Span(EntityType.CODE, 0, 3)))
        val back = TypedLines.read(TypedLines.write(listOf(line))).single()
        assertEquals("a`b", back.text)
        assertTrue(back.spans.isEmpty())
    }

    @Test
    fun пустой_текст_и_пустые_строки() {
        assertEquals(listOf(Line(Kind.PARAGRAPH, "")), TypedLines.read(""))
        roundTrip(listOf(Line(Kind.PARAGRAPH, "a"), Line(Kind.PARAGRAPH, ""), Line(Kind.LIST1, ""), Line(Kind.HEADING2, "")))
    }

    /** Простое сообщение на правку — набранное с косыми там, где знак в тексте (ADR-0048). */
    @Test
    fun простое_сообщение_на_правку_экранировано() {
        val content = MessageContent.text("2*3 = **шесть**")
        val source = QuickMarkdown.source(content)!!
        assertEquals(content.plainText(), QuickMarkdown.parse(source).plainText())
        assertFalse(QuickMarkdown.parse(source).hasMarkup)
    }
}
