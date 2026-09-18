package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Круги по виду группы — то, что принимает сервер (`level_in_private`, `secret_in_public`).
 * Разошлись бы — сообщение получало 400 и крестик, как «nafig» на Redmi 2026-09-18.
 */
class GroupKindCirclesTest {
    @Test
    fun у_личной_группы_только_шифр_и_всем_и_всегда() {
        assertEquals(listOf(-1, 0), GroupKind.Personal.circles.map { it.level })
    }

    @Test
    fun у_публичной_все_открытые_и_нет_шифра() {
        assertEquals(listOf(0, 1, 2, 3), GroupKind.Public.circles.map { it.level })
    }
}
