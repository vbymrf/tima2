package io.tima.core.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Звук — один на пачку, без наложений и без очереди (ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ3). */
class SoundGateTest {

    @Test
    fun пачка_из_50_событий_один_сигнал() {
        val gate = SoundGate()
        val played = (0 until 50).count { gate.ask(nowMs = 1_000L + it * 20, lengthMs = 300) }

        assertEquals(1, played)
        assertEquals(49, gate.skipped)
    }

    @Test
    fun событие_во_время_сигнала_не_звучит_и_не_ждёт() {
        val gate = SoundGate()
        assertTrue(gate.ask(0, lengthMs = 2_000))
        assertFalse(gate.ask(1_000, lengthMs = 2_000), "сигнал ещё играет — наложения нет")

        // Пропущенное не догоняет: после перерыва звучит только новое событие, а
        // промолчавшее так и остаётся промолчавшим — очереди нет.
        assertTrue(gate.ask(7_000, lengthMs = 2_000))
        assertFalse(gate.ask(7_001, lengthMs = 2_000))
    }

    @Test
    fun перерыв_считается_от_конца_сигнала() {
        val gate = SoundGate()
        assertTrue(gate.ask(0, lengthMs = 8_000))

        assertFalse(gate.ask(12_999, lengthMs = 300), "8 с сигнала + 5 с перерыва — ещё тихо")
        assertTrue(gate.ask(13_000, lengthMs = 300))
    }

    @Test
    fun длина_неизвестна_перерыв_самый_длинный() {
        // Уведомитель не достоверный: не знаем длину — молчим дольше, а не накладываем.
        val gate = SoundGate()
        assertTrue(gate.ask(0, lengthMs = null))

        assertFalse(gate.ask(SoundGate.LONGEST_MS + SoundGate.PAUSE_MS - 1, lengthMs = 300))
        assertTrue(gate.ask(SoundGate.LONGEST_MS + SoundGate.PAUSE_MS, lengthMs = 300))
    }

    @Test
    fun ровный_поток_раз_в_секунду_звучит_раз_в_перерыв_а_не_по_кругу() {
        // Сообщения раз в секунду весь час: сигнал не чаще, чем кончается перерыв.
        val gate = SoundGate()
        val played = (0 until 3_600).count { gate.ask(it * 1_000L, lengthMs = 1_000) }

        assertEquals(600, played, "сигнал 1 с + перерыв 5 с — раз в 6 с")
    }
}
