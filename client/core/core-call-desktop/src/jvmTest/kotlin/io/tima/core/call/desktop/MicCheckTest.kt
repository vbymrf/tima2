package io.tima.core.call.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Шкала полосы уровня: децибелы, а не размах. */
class MicCheckTest {

    @Test
    fun тишина_пустая_полоса_полный_размах_полная() {
        assertEquals(0f, MicCheck.level(0))
        assertEquals(1f, MicCheck.level(32767), 0.001f)
    }

    @Test
    fun тихий_голос_виден_на_полосе() {
        // Голос в метре от микрофона ноутбука — около −30 дБ, по размаху это 3 %. На
        // линейной полосе его бы не было видно вовсе, на шкале децибел — половина.
        val quiet = MicCheck.level(1000)
        assertTrue(quiet in 0.4f..0.6f, "тихий голос: $quiet")
    }
}
