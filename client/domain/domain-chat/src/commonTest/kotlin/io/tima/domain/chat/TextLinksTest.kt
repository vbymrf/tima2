package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** Ссылка в тексте (ADR-0050 §3): схема, `www.`, домен популярной зоны; не почта, не код, без точки в конце. */
class TextLinksTest {

    private fun found(text: String) = TextLinks.find(text).map { text.substring(it) }

    @Test
    fun адреса_со_схемой_www_и_домены_зон() {
        assertEquals(listOf("https://ya.ru/?q=a+b", "www.tima.ru", "tima.ru/путь", "example.com"),
            found("см https://ya.ru/?q=a+b, www.tima.ru и tima.ru/путь. Ещё example.com!"))
    }

    @Test
    fun не_адреса() {
        assertEquals(emptyList(), found("почта anna@tima.ru, файл отчёт.txt, версия 2.0.180, слово.слово"))
    }

    @Test
    fun адрес_для_открытия() {
        assertEquals("https://tima.ru", TextLinks.openable("tima.ru"))
        assertEquals("https://www.tima.ru", TextLinks.openable("www.tima.ru"))
        assertEquals("http://x.ru", TextLinks.openable("http://x.ru"))
    }
}
