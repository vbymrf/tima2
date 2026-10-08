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
        assertEquals("#Женя · +799 ••• 01 · с 06.10.2026", returnLabel("u-123456", "Женя", "+79990000101", "с 06.10.2026"))
        assertEquals("u-123456 · +799 ••• 01", returnLabel("u-123456", "", "+79990000101", null))
    }

    @Test
    fun без_имени_и_номера_хвост_идентификатора() {
        assertEquals("69a94f52", returnLabel("69a94f52-f8c860", "", "", null))
        assertEquals("69a94f52 · с 06.10.2026", returnLabel("69a94f52-f8c860", " ", "", "с 06.10.2026"))
        // Без имени, но с ником — «@ник», а не служебное имя (подпись одна на всё приложение, 2026-10-07).
        assertEquals("@shop · с 06.10.2026", returnLabel("69a94f52-f8c860", "", "", "с 06.10.2026", nickname = "shop"))
    }

    @Test
    fun вторая_строка_ник_и_номер_или_виртуальный() {
        assertEquals("@anna · +799 ••• 01", accountDetail("Анна", "anna", "+79990000101", virtual = false, virtualWord = "виртуальный"))
        assertEquals("@work · виртуальный", accountDetail("Работа", "work", "", virtual = true, virtualWord = "виртуальный"))
        // Ник уже стоит первой строкой (имени нет) — второй раз его не пишем.
        assertEquals("виртуальный", accountDetail("", "shop", "", virtual = true, virtualWord = "виртуальный"))
    }
}
