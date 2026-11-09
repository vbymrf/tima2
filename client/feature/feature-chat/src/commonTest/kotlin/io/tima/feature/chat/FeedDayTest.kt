package io.tima.feature.chat

import io.tima.core.words.RussianWords
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** День в ленте (заказчик 2026-11-09): «Сегодня», «Вчера», «29 октября», другой год — с годом. */
class FeedDayTest {

    private val zone = TimeZone.currentSystemDefault()
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime(y, m, d, h, 0).toInstant(zone).toEpochMilliseconds()
    private val now = at(2026, 11, 9)
    private val words = RussianWords.chat

    @Test
    fun день_словами() {
        assertEquals("Сегодня", feedDay(at(2026, 11, 9, 8), words, now))
        assertEquals("Вчера", feedDay(at(2026, 11, 8), words, now))
        assertEquals("29 октября", feedDay(at(2026, 10, 29), words, now))
        assertEquals("31 декабря 2025", feedDay(at(2025, 12, 31), words, now))
    }

    @Test
    fun один_день_по_местным_часам() {
        assertTrue(sameDay(at(2026, 10, 29, 0), at(2026, 10, 29, 23)))
        assertFalse(sameDay(at(2026, 10, 29, 23), at(2026, 10, 30, 0)))
    }
}
