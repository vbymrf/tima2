package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Домен для показа (Решения §4; заказчик 2026-10-24): буквами, как его поймёт человек, — `xn--…`
 * раскодируется; чужие буквы выдаёт цвет в окне, а не `xn--…`.
 */
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
    fun xn_раскодируется_буквами() {
        val kavkaz = "xn--" + Punycode.encode("кавказия")
        assertEquals("кавказия.рф", LinkSafety.displayHost("https://$kavkaz.xn--p1ai/download"))
        assertEquals("api.кавказия.рф", LinkSafety.displayHost("https://api.$kavkaz.xn--p1ai"))
        // Испорченная часть — как есть.
        assertEquals("xn--!!.com", LinkSafety.displayHost("https://xn--!!.com"))
    }

    @Test
    fun смешанные_алфавиты_буквами_и_видны_как_смешанные() {
        // «pаypal» с кириллической «а» на втором месте — показ буквами, окно красит «а» другим тоном.
        val shown = LinkSafety.displayHost("https://xn--pypal-4ve.com/login")
        assertEquals("pаypal.com", shown)
        assertTrue(LinkSafety.mixed(shown))
        assertTrue(LinkSafety.nonLatin(shown[1]))
        assertFalse(LinkSafety.mixed("пример.рф"))
    }

    @Test
    fun punycode_по_образцу_rfc() {
        // RFC 3492, 7.1 (L): «3年B組金八先生» → «3B-ww4c5e180e575a65lsy2b»; имя домена — строчными.
        assertEquals("3b-ww4c5e180e575a65lsy2b", Punycode.encode("3年B組金八先生"))
        assertEquals("3年b組金八先生", Punycode.decode("3b-ww4c5e180e575a65lsy2b"))
    }
}
