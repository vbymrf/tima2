package io.tima.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** «Готово», «Вставить», «Отправить» — один раз до перерисовки (заказчик 2026-10-24, «а»). */
class OnePressTest {

    @Test
    fun второе_нажатие_до_перерисовки_ничего_не_делает_и_пишется() {
        val heard = mutableListOf<Pair<String, Int>>()
        val stop = OnePressLog.listen { tag, skipped, _ -> heard += tag to skipped }
        try {
            val press = OnePress("chat:ask.ok")
            var done = 0
            press.press { done++ }
            press.press { done++ }
            press.press { done++ }
            assertEquals(1, done, "три нажатия до перерисовки — одна вставка")
            // Перерисовка: окно осталось (проверка не прошла) — флажок снят, лишние — одной строкой.
            press.held = false
            press.flush()
            assertEquals(listOf("chat:ask.ok" to 2), heard)
            press.press { done++ }
            assertEquals(2, done)
            press.flush()
            assertEquals(1, heard.size, "без лишних нажатий строки нет")
        } finally {
            stop()
        }
    }
}
