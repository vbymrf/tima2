package io.tima.feature.chat

import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.RichSpan
import io.tima.domain.chat.RichText
import io.tima.domain.chat.SpanKind
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ссылки в сообщениях (заказчик 2026-10-19): синие и нажимаются, под абзацем — «Перейти» с доменом;
 * в простом тексте адрес узнаётся сам, знак препинания после него — не часть адреса.
 */
class LinksTest {

    @Test
    fun адреса_в_простом_тексте() {
        val text = "см. https://a.ru/x, и www.b.com."
        val found = urlsIn(text).map { text.substring(it) }
        assertEquals(listOf("https://a.ru/x", "www.b.com"), found)
        assertEquals("https://www.b.com", openable("www.b.com"))
    }

    /** Заказчик 2026-10-19: адрес в тексте не показывается — его место у пузыря «Перейти · домен». */
    @Test
    fun адрес_уходит_из_текста_и_домен_обрезается() {
        val words = "сайт https://tima.ru для **заказов**"
        val shown = withoutLinks(RichText(words, listOf(RichSpan(SpanKind.LINK, 5, 20, "https://tima.ru"), RichSpan(SpanKind.BOLD, 25, words.length))))
        assertEquals("сайт для **заказов**", shown.text)
        assertEquals(listOf(RichSpan(SpanKind.BOLD, 9, shown.text.length)), shown.spans, "оформление сдвинулось вслед")
        assertEquals("Перейти · tima.ru", linkLabel("Перейти", "https://tima.ru/very/long/path"))
        assertEquals("Магазин · tima.ru", linkLabel("Магазин", "https://tima.ru"), "название — вместо «Перейти»")
        val long = linkLabel("Перейти", "https://a-very-long-domain-name-for-tests.example.com/x")
        assertTrue(long.endsWith("…") && long.length <= "Перейти · ".length + 24, "длинный домен обрезан: $long")
    }

    @Test
    fun ссылка_синяя_и_под_абзацем_перейти() {
        val words = "сайт https://tima.ru"
        val plain = RichContent(listOf(RichBlock.Paragraph(RichText(words))))
        val linked = RichContent(listOf(RichBlock.Paragraph(RichText(words, listOf(RichSpan(SpanKind.LINK, 5, words.length, "https://tima.ru"))))))
        val a = capture("ссылка-нет", 360, 120, dark = false) { RichBody(plain) }
        val b = capture("ссылка-перейти", 360, 120, dark = false) { RichBody(linked) }
        assertTrue(a.difference(b) > 0.01, "у ссылки нет ни цвета, ни «Перейти»")
        val simple = capture("ссылка-простой-текст", 360, 120, dark = false) { PlainWithLinks(words) }
        assertTrue(a.difference(simple) > 0.01, "в простом тексте адрес не узнан")
    }

    @Test
    fun цвет_ссылки_из_сообщения_иначе_из_цветов() {
        val own = capture("ссылка-кнопка-синяя", 360, 100, dark = false) {
            RichBody(RichContent(listOf(RichBlock.Link("https://tima.ru", "button", "", ""))))
        }
        val red = capture("ссылка-кнопка-красная", 360, 100, dark = false) {
            RichBody(RichContent(listOf(RichBlock.Link("https://tima.ru", "button", "", "", color = "red"))))
        }
        assertTrue(own.difference(red) > 0.005, "цвет из сообщения не применился")
    }
}
