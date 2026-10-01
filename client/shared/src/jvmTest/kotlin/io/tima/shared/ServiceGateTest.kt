package io.tima.shared

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Подъёмы службы звонка — не чаще одного в секунду (заказчик 2026-10-01). */
class ServiceGateTest {

    private val raised = mutableListOf<Pair<Long, ServiceWish>>()
    private var lowered = 0

    private fun TestScope.gate() = ServiceGate(
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        now = { testScheduler.currentTime },
        raise = { raised += testScheduler.currentTime to it },
        lower = { lowered++ },
    )

    private fun wish(connectedAt: Long = 0, camera: Boolean = false) =
        ServiceWish("Активный звонок", "Аня", "Завершить", connectedAt, camera)

    @Test
    fun пачка_желаний_за_полсекунды_два_подъёма_и_последнее_содержимое() = runTest {
        // Как было 2026-10-01: вход в комнату, камера, ответ собеседника — за 0,5 с.
        val gate = gate()
        gate.want(wish())
        advanceTimeBy(150)
        gate.want(wish(camera = true))
        advanceTimeBy(350)
        gate.want(wish(connectedAt = 500, camera = true))
        advanceTimeBy(2_000)

        assertEquals(2, raised.size, "подъёмов больше двух")
        assertTrue(raised[1].first - raised[0].first >= ServiceGate.GAP_MS, "подъёмы чаще раза в секунду")
        assertEquals(wish(connectedAt = 500, camera = true), raised[1].second, "ушло не последнее желание")
    }

    @Test
    fun то_же_самое_второй_раз_не_поднимается() = runTest {
        val gate = gate()
        gate.want(wish())
        advanceTimeBy(5_000)
        gate.want(wish())
        advanceTimeBy(5_000)

        assertEquals(1, raised.size)
    }

    @Test
    fun погасили_отложенный_подъём_снят() = runTest {
        val gate = gate()
        gate.want(wish())
        gate.want(wish(camera = true))
        gate.off()
        advanceTimeBy(5_000)

        assertEquals(1, raised.size, "служба поднялась после того, как её погасили")
        assertEquals(1, lowered)
    }

    @Test
    fun следующий_звонок_тоже_не_раньше_секунды() = runTest {
        val gate = gate()
        gate.want(wish())
        gate.off()
        advanceTimeBy(200)
        gate.want(wish())
        advanceTimeBy(2_000)

        assertEquals(2, raised.size)
        assertTrue(raised[1].first - raised[0].first >= ServiceGate.GAP_MS)
    }

    @Test
    fun редкие_желания_уходят_сразу() = runTest {
        val gate = gate()
        gate.want(wish())
        advanceTimeBy(3_000)
        gate.want(wish(connectedAt = 3_000))

        assertEquals(listOf(0L, 3_000L), raised.map { it.first })
    }
}
