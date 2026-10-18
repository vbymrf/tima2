package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Куда клиент не ходит за карточкой ссылки (Решения §4): внутренняя сеть и не-веб. */
class LinkSafetyTest {

    @Test
    fun открытые_адреса() {
        assertTrue(LinkSafety.allowed("https://tima.chat/a?b=1"))
        assertTrue(LinkSafety.allowed("http://example.com:8080/x"))
        assertTrue(LinkSafety.allowed("https://8.8.8.8/"))
    }

    @Test
    fun внутренняя_сеть_и_чужие_схемы() {
        for (url in listOf(
            "http://localhost/", "http://127.0.0.1:9000/", "http://10.1.2.3/", "http://172.20.0.1/",
            "http://192.168.0.1/", "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/",
            "http://[::1]/", "http://[fd00::1]/", "http://router/", "http://nas.local/",
            "ftp://example.com/", "file:///etc/passwd", "javascript:alert(1)",
            "http://user@127.0.0.1/",
        )) assertFalse(LinkSafety.allowed(url), url)
    }

    @Test
    fun имя_узла() {
        assertEquals("tima.chat", LinkSafety.hostOf("https://User:pw@TIMA.chat:443/x"))
        assertEquals("[::1]", LinkSafety.hostOf("http://[::1]:80/"))
    }
}
