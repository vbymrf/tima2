package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Беда 2026-11-15: пропавший после ответа собеседник — не сразу конец, а [PeerAway.WAIT_MS] ожидания. */
class PeerAwayTest {
    @Test
    fun пропавшего_ждут_двадцать_секунд_и_только_потом_конец() {
        val away = PeerAway()
        assertTrue(away.waiting(alone = true, nowMs = 1_000))
        assertTrue(away.waiting(alone = true, nowMs = 1_000 + PeerAway.WAIT_MS - 1))
        assertFalse(away.waiting(alone = true, nowMs = 1_000 + PeerAway.WAIT_MS))
    }

    @Test
    fun вернувшийся_сбрасывает_ожидание() {
        val away = PeerAway()
        away.waiting(alone = true, nowMs = 1_000)
        assertFalse(away.waiting(alone = false, nowMs = 15_000))
        assertFalse(away.started)
        // Пропал снова — отсчёт заново, а не с первого раза.
        assertTrue(away.waiting(alone = true, nowMs = 30_000))
        assertTrue(away.waiting(alone = true, nowMs = 30_000 + PeerAway.WAIT_MS - 1))
    }
}
