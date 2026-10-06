package io.tima.feature.auth

import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnAccountTest {

    @Test
    fun номер_с_кодом_страны_четыре_знака_и_две_последние() {
        assertEquals("+799 ••• 01", maskPhone("+79990000101"))
        assertEquals("899 ••• 01", maskPhone("89990000101"))
        assertEquals("+799 ••• 01", maskPhone("+7 999 000-01-01"))
    }

    @Test
    fun подпись_имя_номер_день() {
        assertEquals("Женя · +799 ••• 01 · с 06.10.2026", returnLabel("u-123456", "Женя", "+79990000101", "с 06.10.2026"))
        assertEquals("+799 ••• 01", returnLabel("u-123456", "", "+79990000101", null))
    }

    @Test
    fun без_имени_и_номера_хвост_идентификатора() {
        assertEquals("…f8c860", returnLabel("69a94f52-f8c860", "", "", null))
        assertEquals("…f8c860 · с 06.10.2026", returnLabel("69a94f52-f8c860", " ", "", "с 06.10.2026"))
    }
}
