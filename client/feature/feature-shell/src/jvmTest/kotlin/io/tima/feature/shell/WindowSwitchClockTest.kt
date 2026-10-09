package io.tima.feature.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/** Песочные часы при смене окна (ПЛАН-(БС) Э4): когда они нужны. */
class WindowSwitchClockTest {
    private val time = TestTimeSource()
    private val clock = WindowSwitchClock(slowMs = 120, time = time)

    @Test
    fun до_первого_перехода_часы_нужны() {
        assertTrue(clock.needed)
    }

    @Test
    fun после_быстрого_перехода_часов_нет() {
        clock.started()
        time += 40.milliseconds
        assertEquals(40, clock.finished())
        assertFalse(clock.needed)
    }

    @Test
    fun после_долгого_перехода_часы_снова_нужны() {
        clock.started()
        time += 40.milliseconds
        clock.finished()
        clock.started()
        time += 900.milliseconds
        clock.finished()
        assertTrue(clock.needed)
    }

    @Test
    fun не_засечённый_переход_не_меняет_решения() {
        assertNull(clock.finished())
        assertTrue(clock.needed)
    }
}
