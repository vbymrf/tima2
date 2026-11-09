package io.tima.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Место сообщения в ленте (заказчик 2026-11-09): переход к старому сообщению доращивает ленту до него. Порядок —
 * тот же, что у `chatPage`: по часам сервера, иначе устройства, при равенстве — по порядку появления.
 */
class ChatDepthTest {

    private val db = testDatabase()
    private val q = db.messagesQueries

    private fun incoming(id: Long, serverTs: Long, chat: String = "chat-1") {
        q.insertIncoming(
            dedup_key = "$chat/$id", server_id = id, chat_id = chat, sender_id = "peer",
            client_ts = serverTs, server_ts = serverTs, state = 0, attempts = 0, body_enc = byteArrayOf(1),
        )
    }

    @Test
    fun место_считается_от_нового_как_в_ленте() {
        for (i in 1L..120L) incoming(i, serverTs = i * 1_000)
        incoming(7, serverTs = 999_999, chat = "другая")
        assertEquals(1, q.chatDepth(120, "chat-1").executeAsOne().toInt(), "последнее — первое")
        assertEquals(120, q.chatDepth(1, "chat-1").executeAsOne().toInt(), "самое старое — сто двадцатое")
        assertEquals(114, q.chatDepth(7, "chat-1").executeAsOne().toInt(), "чужая переписка не в счёт")
        val page = q.chatPage("chat-1", 114).executeAsList()
        assertEquals("chat-1/7", page.last().dedup_key, "страница такой длины кончается на нём")
    }

    @Test
    fun нет_в_переписке_ноль() {
        incoming(1, serverTs = 1_000)
        assertEquals(0, q.chatDepth(55, "chat-1").executeAsOne().toInt())
        assertEquals(0, q.chatDepth(1, "другая").executeAsOne().toInt())
    }
}

/** Первое непрочитанное входящее (ПЛАН-(МП) М2): с него открывается переписка. */
class FirstUnreadTest {

    private val db = testDatabase()
    private val q = db.messagesQueries
    private val stored = io.tima.core.outbox.IncomingState.STORED.ordinal.toLong()
    private val read = io.tima.core.outbox.IncomingState.READ.ordinal.toLong()

    private fun incoming(id: Long, at: Long, state: Long) {
        q.insertIncoming(
            dedup_key = "chat-1/$id", server_id = id, chat_id = "chat-1", sender_id = "peer",
            client_ts = at, server_ts = at, state = state, attempts = 0, body_enc = byteArrayOf(1),
        )
    }

    @Test
    fun самое_старое_непрочитанное() {
        incoming(1, 1_000, read)
        incoming(2, 2_000, stored)
        incoming(3, 3_000, stored)
        assertEquals(2L, q.firstUnread("chat-1", stored).executeAsOneOrNull())
    }

    @Test
    fun всё_прочитано_пусто() {
        incoming(1, 1_000, read)
        assertEquals(null, q.firstUnread("chat-1", stored).executeAsOneOrNull())
    }
}
