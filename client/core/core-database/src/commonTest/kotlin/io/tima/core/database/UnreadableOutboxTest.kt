package io.tima.core.database

import io.tima.core.encryption.LocalStoreFieldCipher
import io.tima.core.outbox.Outbox
import io.tima.core.outbox.OutboxState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Беда 2026-11-20 «чужая строка в очереди»: строка неотправленного записана чужим ключом покоя. Прежде чтение очереди
 * падало (`requireNotNull`) — и роняло приложение на каждом проходе. Теперь строка откладывается «мёртвой», не уходит на
 * сервер, а очередь идёт дальше.
 */
class UnreadableOutboxTest {

    private val db = testDatabase()
    private var now = 1_000_000L

    @Test
    fun чужая_строка_откладывается_и_очередь_идёт_дальше() {
        // Строка, положенная другой установкой: другой секрет — другой ключ покоя.
        val foreign = Outbox(SqlOutboxStore(db, LocalStoreFieldCipher(ByteArray(32) { 99 })), nowMs = { now }, stampMs = { now })
        foreign.enqueue("чужая", "chat-1", byteArrayOf(1, 2, 3))
        now += 1
        val skipped = mutableListOf<String>()
        val store = SqlOutboxStore(db, testCipher(), onUnreadable = { skipped += it })
        val mine = Outbox(store, nowMs = { now }, stampMs = { now })
        mine.enqueue("моя", "chat-1", byteArrayOf(4))

        val pending = store.pending()
        assertEquals(listOf("моя"), pending.map { it.dedupKey }, "чтение очереди не падает и чужой не отдаёт")
        assertEquals(listOf("чужая"), skipped)
        val row = db.messagesQueries.byDedupKey("чужая").executeAsOne()
        assertEquals(OutboxState.DEAD.ordinal.toLong(), row.state)
        assertEquals("unreadable_body", row.fail_reason)
        assertEquals("моя", store.nextQueued("chat-1", now)?.dedupKey, "очередь не встала на чужой")
        assertNull(store.byDedupKey("чужая"))
        assertTrue(skipped.size >= 1)
    }
}
