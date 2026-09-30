package io.tima.core.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Часы сервера по заголовку `Date` (ПЛАН-ВЫХОДА-ИЗ-АККАУНТА.md, А2).
 *
 * 2026-09-30 часы ПК спешили на 3 мин 10 с, и сервер не принимал ни одну подпись
 * обновления токена: окно у него ±2 минуты. Поправка по его же времени это снимает.
 */
class ServerClockTest {

    @AfterTest
    fun сбросить() = ServerClock.reset()

    @Test
    fun дата_http_разбирается() {
        assertEquals(1_790_776_089_000L, parseHttpDate("Wed, 30 Sep 2026 13:48:09 GMT"))
    }

    @Test
    fun чужое_не_притворяется_датой() {
        assertNull(parseHttpDate("вчера"))
        assertNull(parseHttpDate("Wed, 30 Foo 2026 13:48:09 GMT"))
        assertNull(parseHttpDate(""))
    }

    @Test
    fun поправка_берётся_из_ответа_сервера() {
        val server = 1_790_776_089_000L
        // часы устройства спешат на 3 мин 10 с — как у ПК 2026-09-30
        val local = server + 190_000L
        ServerClock.observe("Wed, 30 Sep 2026 13:48:09 GMT", local)

        assertEquals(-190_000L, ServerClock.offsetMillis)
        assertEquals(server, ServerClock.now(local), "подпись должна нести время сервера")
    }

    @Test
    fun мусорный_заголовок_поправку_не_трогает() {
        ServerClock.observe("Wed, 30 Sep 2026 13:48:09 GMT", 1_790_776_089_000L + 5_000L)
        ServerClock.observe("не дата", 0L)
        assertEquals(-5_000L, ServerClock.offsetMillis)
    }
}
