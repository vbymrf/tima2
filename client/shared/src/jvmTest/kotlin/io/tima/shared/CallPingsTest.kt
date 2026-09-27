package io.tima.shared

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * События звонка доходят все и по порядку — Redmi, отчёт FTPB 2026-09-27.
 *
 * Лента принесла одной пачкой «звонит» нового звонка и «завершён» прошлого (8 мс между
 * ними). Прежнее значение хранило только последнее, и входящий пропадал: окно 0 оставалось
 * на прошлом звонке, «Ответить» в уведомлении ничего не делало.
 */
class CallPingsTest {

    @Test
    fun входящий_не_затирается_концом_прошлого_звонка() = runBlocking {
        val pings = CallPings()
        // Окна ещё нет — оба события пришли до того, как их стали читать.
        pings.send("0b3a176e|a201146f|video")
        pings.send("конец|6247acf3|ended")

        assertEquals(
            listOf("0b3a176e|a201146f|video", "конец|6247acf3|ended"),
            pings.pings.take(2).toList(),
        )
    }

    @Test
    fun съеденное_не_приходит_второй_раз() = runBlocking {
        // Окно пересобрали — повтор старого «звонит» поднял бы входящий по кончившемуся звонку.
        val pings = CallPings()
        pings.send("первый|x|video")
        assertEquals(listOf("первый|x|video"), pings.pings.take(1).toList())
        pings.send("второй|y|audio")
        assertEquals(listOf("второй|y|audio"), pings.pings.take(1).toList())
    }
}
