package io.tima.feature.chat

import io.tima.domain.chat.CallRecord
import kotlin.test.Test
import kotlin.test.assertEquals

/** Объединение звонков подряд (ПЛАН-(ВС) ВС2, заказчик 2026-11-20): соседние с одним человеком или группой — одной пачкой. */
class MergeCallsTest {
    private val me = "me"
    private var n = 0
    private fun call(from: String, to: String, group: String = "") =
        CallRecord(callId = "c${n++}", video = false, state = "ended", initiatorId = from, peerId = to, createdAt = 1000L - n, groupId = group)

    @Test
    fun соседние_с_одним_человеком_любого_направления_вместе() {
        val calls = listOf(call("anna", me), call(me, "anna"), call("anna", me), call("boris", me), call("anna", me))
        assertEquals(listOf(3, 1, 1), mergeRuns(calls, me).map { it.size }, "Анна, Анна, Анна | Борис | Анна — не подряд")
    }

    @Test
    fun групповые_по_группе() {
        val calls = listOf(call("anna", me, "g1"), call("boris", me, "g1"), call("anna", me), call("anna", me, "g2"))
        assertEquals(listOf(2, 1, 1), mergeRuns(calls, me).map { it.size })
    }

    @Test
    fun порядок_журнала_сохраняется() {
        val calls = listOf(call("anna", me), call("anna", me), call("boris", me))
        assertEquals(calls.map { it.callId }, mergeRuns(calls, me).flatten().map { it.callId })
    }
}
