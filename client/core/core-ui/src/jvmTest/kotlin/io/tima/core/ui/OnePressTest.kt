package io.tima.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** «Готово», «Вставить», «Отправить» — один раз до перерисовки (заказчик 2026-10-24, «а»). */
class OnePressTest {

    @Test
    fun второе_нажатие_до_перерисовки_ничего_не_делает() {
        val press = OnePress()
        var done = 0
        press.press { done++ }
        press.press { done++ }
        press.press { done++ }
        assertEquals(1, done, "три нажатия до перерисовки — одна вставка")
        // Окно осталось (проверка не прошла) — после перерисовки флажок снят, нажать можно снова.
        press.held = false
        press.press { done++ }
        assertEquals(2, done)
    }
}
