package io.tima.shared

import io.tima.core.network.ChannelPing
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Экономичный режим канала (заказчик 2026-10-06): по умолчанию выключен, 90 секунд. */
class ChannelEconomyTest {

    @AfterTest
    fun back() {
        ChannelPing.economySeconds = 0
    }

    @Test
    fun по_умолчанию_выключен_и_90_секунд() {
        val choice = ChannelEconomy.read(emptyMap())
        assertFalse(choice.on)
        assertEquals(90, choice.seconds)
        ChannelEconomy.apply(emptyMap())
        assertEquals(ChannelPing.NORMAL_MS, ChannelPing.intervalMs())
    }

    @Test
    fun включённый_задаёт_перекличку_и_держится_в_границах() {
        ChannelEconomy.apply(mapOf(ChannelEconomy.KEY_ON to "1", ChannelEconomy.KEY_SECONDS to "120"))
        assertEquals(120_000L, ChannelPing.intervalMs())
        ChannelEconomy.apply(mapOf(ChannelEconomy.KEY_ON to "1", ChannelEconomy.KEY_SECONDS to "5"))
        assertEquals(ChannelEconomy.MIN_SECONDS * 1000L, ChannelPing.intervalMs())
        ChannelEconomy.apply(mapOf(ChannelEconomy.KEY_ON to "0", ChannelEconomy.KEY_SECONDS to "120"))
        assertEquals(ChannelPing.NORMAL_MS, ChannelPing.intervalMs())
    }
}
