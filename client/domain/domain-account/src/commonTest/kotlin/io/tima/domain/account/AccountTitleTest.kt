package io.tima.domain.account

import kotlin.test.Test
import kotlin.test.assertEquals

/** Подпись аккаунта одна на всё приложение (заказчик 2026-10-07). */
class AccountTitleTest {

    @Test
    fun имя_потом_ник_потом_служебное_имя() {
        assertEquals("Анна", AccountTitle.of("Анна", "anna", "69a94f52-f8c860"))
        assertEquals("@anna", AccountTitle.of(" ", "anna", "69a94f52-f8c860"))
        assertEquals("@anna", AccountTitle.of("", "@anna", "69a94f52-f8c860"))
        // Ни имени, ни ника — служебное имя, а не пустота.
        assertEquals("69a94f52", AccountTitle.of("", "", "69a94f52-f8c860"))
    }
}
