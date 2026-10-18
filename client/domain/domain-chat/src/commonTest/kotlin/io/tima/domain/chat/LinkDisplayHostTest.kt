package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** Домен для показа (Решения §4): буквы разных алфавитов в одной части — `xn--…`, один алфавит — как есть. */
class LinkDisplayHostTest {

    @Test
    fun латиница_как_есть_без_www() {
        assertEquals("example.com", LinkSafety.displayHost("https://www.example.com/a/b"))
    }

    @Test
    fun один_алфавит_как_есть() {
        assertEquals("пример.рф", LinkSafety.displayHost("https://пример.рф/"))
    }

    @Test
    fun смешанные_алфавиты_как_xn() {
        // «pаypal» с кириллической «а» на втором месте.
        val shown = LinkSafety.displayHost("https://pаypal.com/login")
        assertEquals("xn--pypal-4ve.com", shown)
    }

    @Test
    fun punycode_по_образцу_rfc() {
        // RFC 3492, 7.1 (L): «3年B組金八先生» → «3B-ww4c5e180e575a65lsy2b»; имя домена — строчными.
        assertEquals("3b-ww4c5e180e575a65lsy2b", Punycode.encode("3年B組金八先生"))
    }
}
