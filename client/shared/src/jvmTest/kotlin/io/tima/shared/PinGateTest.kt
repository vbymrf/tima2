package io.tima.shared

import io.tima.domain.account.PinRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Замок пин-кода на процесс (ПЛАН-(ПН) Р9): 5 минут в фоне — снова замок, меньше — нет. */
class PinGateTest {

    @Test
    fun пять_минут_в_фоне_закрывают_снова() {
        PinGate.opened("u-pin-1")
        val before = PinGate.relock.value
        PinGate.visible(false, now = 1_000)
        PinGate.visible(true, now = 1_000 + PinRules.AWAY_MS - 1)
        assertTrue(PinGate.isOpen("u-pin-1"), "меньше пяти минут — без замка")
        assertEquals(before, PinGate.relock.value)

        PinGate.visible(false, now = 10_000)
        PinGate.visible(true, now = 10_000 + PinRules.AWAY_MS)
        assertFalse(PinGate.isOpen("u-pin-1"), "пять минут — замок")
        assertEquals(before + 1, PinGate.relock.value)
    }
}
