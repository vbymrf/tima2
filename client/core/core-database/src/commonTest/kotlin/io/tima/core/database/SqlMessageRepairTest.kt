package io.tima.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

/** Двойники из-за округлённого номера: остаётся первая строка, разные сообщения не трогаются. */
class SqlMessageRepairTest {

    private val db = testDatabase()
    private val q = db.messagesQueries

    private fun incoming(key: String, serverId: Long, sender: String = "u-1", chat: String = "chat-1") =
        q.insertIncoming(key, serverId, chat, sender, 1, 1, 3, 0, byteArrayOf(1))

    @Test
    fun округлённый_и_настоящий_номер_одного_сообщения_схлопываются() {
        incoming("chat-1/956072612176218200", 956072612176218200)
        incoming("chat-1/956072612176218224", 956072612176218224)
        incoming("chat-1/812753833107720486", 812753833107720486) // другое сообщение

        assertEquals(1L, SqlMessageRepair(db).dropRoundedTwins())
        assertEquals(
            listOf("chat-1/812753833107720486", "chat-1/956072612176218200"),
            q.chatPage("chat-1", 10).executeAsList().map { it.dedup_key }.sorted(),
        )
    }

    /** Беда 2026-10-21: своё отправленное и его копия с сервера одного номера — остаётся исходящая. */
    @Test
    fun свой_двойник_убирается_исходящая_остаётся() {
        q.insertQueued("out-1", "g-1", "me", 1, 0, 0, null, null, 2, byteArrayOf(1), 0, 0, 0)
        q.markSent(3, 5, 1, "out-1")
        incoming("g-1/5", 5, sender = "me", chat = "g-1")
        incoming("g-1/6", 6, sender = "u-2", chat = "g-1")
        incoming("g-2/5", 5, sender = "u-3", chat = "g-2")
        assertEquals(1L, SqlMessageRepair(db).dropOwnEchoes("g-1"))
        assertEquals(listOf("g-1/6", "out-1"), q.chatPage("g-1", 10).executeAsList().map { it.dedup_key }.sorted())
        assertEquals(0L, SqlMessageRepair(db).dropOwnEchoes(), "в другой переписке тот же номер — чужое сообщение")
    }

    @Test
    fun в_разных_переписках_и_от_разных_отправителей_не_трогаются() {
        incoming("chat-1/956072612176218200", 956072612176218200)
        incoming("chat-2/956072612176218224", 956072612176218224, chat = "chat-2")
        incoming("chat-1/956072612176218224", 956072612176218224, sender = "u-2")
        assertEquals(0L, SqlMessageRepair(db).dropRoundedTwins())
    }
}
