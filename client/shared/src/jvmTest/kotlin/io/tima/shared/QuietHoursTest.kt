package io.tima.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** «Не показывать уведомления с … до …» (заказчик 2026-10-01). */
class QuietHoursTest {

    private fun at(h: Int, m: Int = 0) = h * 60 + m

    @Test
    fun через_полночь_с_23_до_8() {
        val q = QuietHours(on = true, from = at(23), to = at(8))
        assertTrue(q.covers(at(23)))
        assertTrue(q.covers(at(2, 30)))
        assertTrue(q.covers(at(7, 59)))
        assertFalse(q.covers(at(8)), "конец не входит")
        assertFalse(q.covers(at(12)))
    }

    @Test
    fun в_пределах_дня_с_13_до_15() {
        val q = QuietHours(on = true, from = at(13), to = at(15))
        assertTrue(q.covers(at(14)))
        assertFalse(q.covers(at(15)))
        assertFalse(q.covers(at(23)))
    }

    @Test
    fun выключено_или_пустой_промежуток_не_тихо() {
        assertFalse(QuietHours(on = false, from = at(0), to = at(23)).covers(at(12)))
        assertFalse(QuietHours(on = true, from = at(9), to = at(9)).covers(at(9)))
    }

    @Test
    fun настройки_по_умолчанию_ночь_и_выключено() {
        val q = QuietHours.read(emptyMap())
        assertEquals(QuietHours(on = false, from = at(23), to = at(8), calls = true, messages = true), q)
        assertEquals(
            QuietHours(on = true, from = at(22, 30), to = at(7)),
            QuietHours.read(mapOf(QuietHours.KEY_ON to "1", QuietHours.KEY_FROM to "1350", QuietHours.KEY_TO to "420")),
        )
    }

    @Test
    fun галочки_звонки_и_сообщения_читаются_отдельно() {
        val q = QuietHours.read(mapOf(QuietHours.KEY_ON to "1", QuietHours.KEY_CALLS to "0", QuietHours.KEY_MESSAGES to "1"))
        assertFalse(q.calls, "звонки не глушатся")
        assertTrue(q.messages)
        val off = QuietHours(on = true, from = at(0), to = at(23, 59), calls = false, messages = false)
        assertFalse(off.callsMuted())
        assertFalse(off.messagesMuted())
    }
}
