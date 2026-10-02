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

    @Test
    fun в_разных_переписках_и_от_разных_отправителей_не_трогаются() {
        incoming("chat-1/956072612176218200", 956072612176218200)
        incoming("chat-2/956072612176218224", 956072612176218224, chat = "chat-2")
        incoming("chat-1/956072612176218224", 956072612176218224, sender = "u-2")
        assertEquals(0L, SqlMessageRepair(db).dropRoundedTwins())
    }
}
