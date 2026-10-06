package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Просьба о ключах переписки (2026-10-06): сколько номеров назвать, решает предел тела у
 * сервера, а не счёт — номера кладутся, пока тело влезает, новые первыми.
 */
class RecoverBodyTest {

    @Test
    fun номера_кладутся_пока_влезает_тело() {
        val ids = (1L..5000L).map { 9_000_000_000L - it }
        val body = HistoryApi.recoverBody("s".repeat(86), ids)
        assertTrue(body.encodeToByteArray().size <= HistoryApi.RECOVER_BODY_BYTES, "тело ${body.length} больше предела")
        assertTrue(body.endsWith("]}"))
        assertTrue(body.contains("\"missing\":[${ids.first()},"), "первым должен идти самый новый")
        // Влезло почти всё, что могло: следующий номер уже не помещается.
        val next = ids[body.count { it == ',' }]
        assertTrue(body.length + next.toString().length + 1 > HistoryApi.RECOVER_BODY_BYTES - 2)
    }

    @Test
    fun без_подписи_и_номеров() {
        assertEquals("{\"missing\":[]}", HistoryApi.recoverBody(null, emptyList()))
        assertEquals("{\"signature\":\"ab\",\"missing\":[3,2]}", HistoryApi.recoverBody("ab", listOf(3, 2)))
    }
}
