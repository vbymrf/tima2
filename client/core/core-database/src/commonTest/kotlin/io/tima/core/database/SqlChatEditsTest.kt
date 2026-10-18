package io.tima.core.database

import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxState
import io.tima.domain.chat.DecodedLine
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.ObserveChat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правка в ленте (ADR-0045): строкой не идёт, ложится на исходное того же автора, не считается
 * непрочитанным и не становится последней строкой в списке переписок.
 */
class SqlChatEditsTest {

    /** Кодек-подделка: правка — новый текст целиком, последняя побеждает. */
    private object EditCodec : MessageBodyCodec {
        override fun encodeText(text: String): ByteArray = text.encodeToByteArray()
        override fun decodeText(body: ByteArray): String = body.decodeToString()
        override fun decodeEdited(body: ByteArray, edits: List<ByteArray>): DecodedLine =
            DecodedLine(edits.lastOrNull()?.decodeToString() ?: body.decodeToString(), null)
    }

    private val db = testDatabase()
    private val cipher = testCipher()
    private val outbox = SqlOutboxStore(db, cipher)
    private val inbox = SqlInboxStore(db, cipher)
    private val feed = SqlChatFeed(db, EditCodec, cipher, ME)
    private val chat = ObserveChat(feed)

    private fun incoming(messageId: Long, text: String, sender: String = PEER, editOf: Long = 0, ts: Long = messageId * 1_000) {
        inbox.putIfAbsent(
            IncomingEntry(
                chatId = CHAT, messageId = messageId, envelope = byteArrayOf(9),
                state = IncomingState.RECEIVED, receivedAtMs = ts, sentAtMs = ts,
            ),
        )
        inbox.storeParsed(CHAT, messageId, text.encodeToByteArray(), sender, -1, 0, editOf = editOf)
        inbox.update(inbox.byKey(CHAT, messageId)!!.copy(state = IncomingState.STORED))
    }

    private fun outgoing(dedupKey: String, text: String, serverId: Long?, editOf: Long = 0, state: OutboxState = OutboxState.SENT) {
        outbox.putIfAbsent(
            OutboxEntry(dedupKey = dedupKey, chatId = CHAT, body = text.encodeToByteArray(), editOf = editOf, createdAtMs = 10_000),
        )
        outbox.update(outbox.byDedupKey(dedupKey)!!.copy(state = state, serverMessageId = serverId))
        if (serverId != null && state == OutboxState.SENT) {
            db.messagesQueries.markSent(OutboxState.SENT.ordinal.toLong(), serverId, 10_000, dedupKey)
        }
    }

    @Test
    fun правка_ложится_на_исходное_и_строкой_не_идёт() = runTest {
        incoming(1, "было")
        incoming(2, "стало", editOf = 1)
        val lines = chat.page(CHAT).first()
        assertEquals(1, lines.size, "правка отдельной строкой не показывается")
        assertEquals("стало", lines.single().text)
        assertEquals(1, lines.single().edits)
    }

    @Test
    fun чужая_правка_не_ложится() = runTest {
        incoming(1, "было")
        incoming(2, "подмена", sender = "u-чужой", editOf = 1)
        val line = chat.page(CHAT).first().single()
        assertEquals("было", line.text)
        assertEquals(0, line.edits)
    }

    @Test
    fun правка_без_исходного_не_видна() = runTest {
        incoming(5, "правка того, чего у меня нет", editOf = 3)
        assertTrue(chat.page(CHAT).first().isEmpty(), "вступивший позже правку без исходного не видит")
    }

    @Test
    fun своя_правка_ложится_а_отказанная_нет() = runTest {
        outgoing("o-1", "моё", serverId = 42)
        outgoing("o-2", "моё исправленное", serverId = 43, editOf = 42)
        assertEquals("моё исправленное", chat.page(CHAT).first().single().text)
        outgoing("o-3", "отказано", serverId = null, editOf = 42, state = OutboxState.DEAD)
        val line = chat.page(CHAT).first().single()
        assertEquals("моё исправленное", line.text, "отказанной правки у собеседника нет — и у меня её быть не должно")
        assertEquals(1, line.edits)
    }

    @Test
    fun не_больше_трёх_правок() = runTest {
        incoming(1, "было")
        for (i in 2L..5L) incoming(i, "правка $i", editOf = 1)
        val line = chat.page(CHAT).first().single()
        assertEquals(3, line.edits)
        assertEquals("правка 4", line.text, "четвёртую сервер не принимает — и показывать её неоткуда")
    }

    @Test
    fun правка_не_непрочитанное_и_не_последняя_строка() = runTest {
        incoming(1, "было")
        incoming(2, "стало", editOf = 1, ts = 99_000)
        assertEquals(1L, db.messagesQueries.unreadInChat(CHAT, IncomingState.STORED.ordinal.toLong()).executeAsOne())
        val summary = db.chatsQueries.chatList(IncomingState.READ.ordinal.toLong(), 10).executeAsList().single()
        assertEquals(1_000L, summary.last_at, "последняя строка — исходное, а не правка")
        assertEquals(1L, summary.unread)
    }

    @Test
    fun исходное_для_правки_с_её_правками() = runTest {
        outgoing("o-1", "моё", serverId = 42)
        outgoing("o-2", "первая", serverId = 43, editOf = 42)
        val original = feed.original(CHAT, 42)!!
        assertEquals("моё", original.body.decodeToString())
        assertEquals(listOf("первая"), original.edits.map { it.decodeToString() })
        assertTrue(original.own)
        incoming(7, "чужое")
        assertEquals(false, feed.original(CHAT, 7)!!.own)
        assertNull(feed.original(CHAT, 999))
    }

    private companion object {
        const val CHAT = "chat-1"
        const val ME = "u-я"
        const val PEER = "u-собеседник"
    }
}
