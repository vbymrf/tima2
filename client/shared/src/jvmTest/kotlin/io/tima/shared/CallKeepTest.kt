package io.tima.shared

import io.tima.feature.shell.Window
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Открытое окно живёт у процесса (заказчик 2026-09-30, 1а): Android пересоздаёт главное окно
 * сам, и новое обязано показать то же окно, а не вернуться на «Телефон».
 */
class CallKeepTest {

    @Test
    fun пересозданное_окно_видит_то_же_открытое_окно() {
        val было = CallKeep.window("проверка-окна")
        было.value = Window.Call

        val стало = CallKeep.window("проверка-окна")

        assertSame(было, стало)
        assertEquals(Window.Call, стало.value)
    }

    @Test
    fun новое_устройство_начинает_с_телефона() {
        assertEquals(Window.Phone, CallKeep.window("проверка-нового").value)
    }
}
