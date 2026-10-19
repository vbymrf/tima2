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
