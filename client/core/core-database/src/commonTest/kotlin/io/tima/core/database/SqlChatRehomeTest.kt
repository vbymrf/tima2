package io.tima.core.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Переезд переписки под текущую личность собеседника (ДУ6, Р26): человек один — переписка одна.
 */
class SqlChatRehomeTest {

    private val db = testDatabase()
    private val q = db.messagesQueries

    private fun place(id: String, chat: String) = q.insertQueued(
        dedup_key = id, chat_id = chat, sender_id = "me",
        client_ts = 100, state = 0, attempts = 0,
        next_attempt_at = null, reply_to = null, level = -1, body_enc = byteArrayOf(1), thread_root = 0, kind = 1,
    )

    @Test
    fun переписка_переезжает_с_именем_и_сообщениями() {
        db.chatsQueries.upsertChat(chatId = "chat-старый", kind = 0, titleEnc = byteArrayOf(9), peerId = "u-прежняя")
        place("раз", "chat-старый")
        place("два", "chat-старый")

        SqlChatRehome(db).rehome("chat-старый", "chat-новый", "u-новая")

        assertEquals(listOf("раз", "два"), q.chatPage("chat-новый", 10).executeAsList().map { it.dedup_key }.sorted().reversed())
        assertEquals(0, q.chatPage("chat-старый", 10).executeAsList().size)
        val row = db.chatsQueries.chatById("chat-новый").executeAsOneOrNull()!!
        assertEquals("u-новая", row.peer_id)
        assertNull(db.chatsQueries.chatById("chat-старый").executeAsOneOrNull())
    }

    @Test
    fun к_уже_существующему_чату_сообщения_добавляются() {
        db.chatsQueries.upsertChat(chatId = "chat-старый", kind = 0, titleEnc = null, peerId = "u-прежняя")
        db.chatsQueries.upsertChat(chatId = "chat-новый", kind = 0, titleEnc = null, peerId = "u-новая")
        place("старое", "chat-старый")
        place("новое", "chat-новый")

        SqlChatRehome(db).rehome("chat-старый", "chat-новый", "u-новая")

        assertEquals(2, q.chatPage("chat-новый", 10).executeAsList().size)
        assertNull(db.chatsQueries.chatById("chat-старый").executeAsOneOrNull())
    }
}
