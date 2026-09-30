package io.tima.shared

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Лента звонков: опоздавшая подсказка — не сброс (заказчик 2026-09-30, 1а); пропущенные до
 * входа устройства не уведомляются (2а).
 */
class CallsFeedTest {

    @Test
    fun подсказка_новее_своего_номера_забирает_после_него() = runTest {
        var asked = false
        assertEquals(448L, callsFrom(hint = 450, mine = 448) { asked = true; 450 })
        assertFalse(asked, "вершину у сервера не спрашиваем — подсказка и так новее")
    }

    @Test
    fun своя_подсказка_ничего_не_делает() = runTest {
        assertNull(callsFrom(hint = 448, mine = 448) { error("не спрашивать") })
    }

    @Test
    fun опоздавшая_подсказка_не_начинает_ленту_заново() = runTest {
        // ПК 2026-09-30: 14 подсказок «seen», первая забрала до 448, остальные — 436, 437…
        assertNull(callsFrom(hint = 436, mine = 448) { 448 })
    }

    @Test
    fun опоздавшая_подсказка_при_новом_на_сервере_забирает_после_своего() = runTest {
        assertEquals(448L, callsFrom(hint = 436, mine = 448) { 451 })
    }

    @Test
    fun сервер_начал_ленту_заново_значит_с_нуля() = runTest {
        assertEquals(0L, callsFrom(hint = 3, mine = 448) { 3 })
    }

    @Test
    fun сервер_не_ответил_ждём_следующей_подсказки() = runTest {
        assertNull(callsFrom(hint = 3, mine = 448) { null })
    }

    @Test
    fun пропущенный_до_входа_устройства_не_уведомляется() {
        assertTrue(missedBeforeDevice(atMs = 1_000, since = 2_000))
        assertFalse(missedBeforeDevice(atMs = 3_000, since = 2_000), "после входа — уведомляется")
        assertFalse(missedBeforeDevice(atMs = 0, since = 2_000), "время неизвестно — уведомляется")
    }
}
