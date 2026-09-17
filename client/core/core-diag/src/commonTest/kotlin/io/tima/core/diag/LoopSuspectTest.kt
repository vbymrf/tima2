package io.tima.core.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Журнал замечает работу по кругу.**
 *
 * Заведено 2026-09-17 вместе с кодом [LogCode.LOOP_SUSPECT]. Повод — настоящая беда, о
 * которой журнал не сказал ни строкой: приложение уходило в круг «запись в базу → запрос
 * будит слушателя → снова запись» и переставало отвечать, а каждый шаг круга при этом
 * завершался успешно. Разбор — `doc_mig/БЕДЫ/2026-09-17-зависание-на-переписке.md`.
 */
class LoopSuspectTest {

    /** Часы под управлением: всплеск должен укладываться в окно по времени, а не «быстро». */
    private class Clock(var millis: Long = 0) {
        fun tick(by: Long): Long {
            millis += by
            return millis
        }
    }

    private fun diary(clock: Clock) = Diary(now = { clock.millis })

    @Test
    fun круг_назван_одной_строкой_а_не_каждым_повтором() {
        val clock = Clock()
        val diary = diary(clock)
        repeat(300) {
            clock.tick(1)
            diary.note("TEST-SPIN", "шаг круга")
        }
        val suspects = diary.tail().filter { it.code == LogCode.LOOP_SUSPECT }
        assertEquals(
            1,
            suspects.size,
            "строка про круг обязана быть ровно одна: журнал не должен тонуть в том, " +
                "что взялся описывать",
        )
        assertTrue(
            suspects.single().details.any { it.first == "код" && it.second == "TEST-SPIN" },
            "строка обязана называть код, который повторялся: без него она бесполезна",
        )
    }

    @Test
    fun после_названного_круга_повторы_не_копятся_в_журнале() {
        val clock = Clock()
        val diary = diary(clock)
        repeat(300) {
            clock.tick(1)
            diary.note("TEST-SPIN", "шаг круга")
        }
        val spins = diary.tail().count { it.code == "TEST-SPIN" }
        assertTrue(
            spins in 1..Diary.BURST_LIMIT,
            "после названного всплеска записи кода вести незачем: их $spins, " +
                "а порог ${Diary.BURST_LIMIT}",
        )
    }

    @Test
    fun редкие_записи_кругом_не_считаются() {
        val clock = Clock()
        val diary = diary(clock)
        // Втрое больше порога, но каждая запись — за пределом окна от предыдущей.
        repeat(Diary.BURST_LIMIT * 3) {
            clock.tick(Diary.BURST_WINDOW + 1)
            diary.note("TEST-RARE", "обычная жизнь")
        }
        assertTrue(
            diary.tail().none { it.code == LogCode.LOOP_SUSPECT },
            "редкие записи кругом не являются, сколько бы их ни было всего",
        )
    }

    @Test
    fun после_паузы_код_снова_пишется() {
        val clock = Clock()
        val diary = diary(clock)
        repeat(300) {
            clock.tick(1)
            diary.note("TEST-SPIN", "шаг круга")
        }
        clock.tick(Diary.BURST_WINDOW * 2)
        diary.note("TEST-SPIN", "жизнь после всплеска")
        assertTrue(
            diary.tail().last { it.code == "TEST-SPIN" }.text == "жизнь после всплеска",
            "молчание обязано кончаться вместе со всплеском: иначе код замолчит навсегда",
        )
    }
}
