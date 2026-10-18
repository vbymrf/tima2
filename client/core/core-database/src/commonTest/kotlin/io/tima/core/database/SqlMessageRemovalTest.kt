package io.tima.core.database

import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.IncomingState
import io.tima.domain.chat.DecodedLine
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessagePicks
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.PickResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Удаление в базе устройства (Решения §8) и Подборка (§11.2): у себя — строки нет, у всех —
 * «Сообщение удалено» без содержимого; правки уходят вместе с исходным.
 */
class SqlMessageRemovalTest {

    private object Plain : MessageBodyCodec {
        override fun encodeText(text: String) = text.encodeToByteArray()
        override fun decodeText(body: ByteArray) = body.decodeToString()
        override fun decodeEdited(body: ByteArray, edits: List<ByteArray>) =
            DecodedLine(edits.lastOrNull()?.decodeToString() ?: body.decodeToString(), null)
    }

    private val db = testDatabase()
    private val cipher = testCipher()
    private val inbox = SqlInboxStore(db, cipher)
    private val feed = SqlChatFeed(db, Plain, cipher, "u-я")
    private val removal = SqlMessageRemoval(db)

    private fun incoming(id: Long, text: String, editOf: Long = 0) {
        inbox.putIfAbsent(IncomingEntry(chatId = "c", messageId = id, envelope = byteArrayOf(9), state = IncomingState.RECEIVED, receivedAtMs = id, sentAtMs = id))
        inbox.storeParsed("c", id, text.encodeToByteArray(), "u-он", -1, 0, editOf = editOf)
        inbox.update(inbox.byKey("c", id)!!.copy(state = IncomingState.STORED))
    }

    @Test
    fun у_всех_строка_остаётся_удалённой_без_содержимого_и_правок() = runTest {
        incoming(1, "было")
        incoming(2, "правка", editOf = 1)
        removal.markDeleted("c", 1)
        val line = ObserveChat(feed).page("c").first().single()
        assertTrue(line.deleted)
        assertNull(line.text)
        assertEquals(0, line.edits)
        assertEquals(1L, db.messagesQueries.countAll().executeAsOne(), "правка стёрта вместе с исходным")
        assertNull(feed.original("c", 1), "удалённое не правится")
    }

    @Test
    fun у_себя_строки_нет() = runTest {
        incoming(1, "было")
        incoming(2, "правка", editOf = 1)
        removal.forMe("c", "c/1", 1)
        assertTrue(ObserveChat(feed).page("c").first().isEmpty())
        assertEquals(0L, db.messagesQueries.countAll().executeAsOne())
    }

    @Test
    fun подборка_до_двадцати_на_уровень() {
        val picks = MessagePicks(SqlPicks(db), nowMs = { 0 })
        repeat(MessagePicks.MAX) { assertEquals(PickResult.Added, picks.add("self", "c", it + 1L)) }
        assertEquals(PickResult.Already, picks.add("self", "c", 1))
        assertEquals(PickResult.Full, picks.add("self", "c", 99))
        assertEquals(PickResult.Added, picks.add("group:g", "g", 99), "у другого уровня — своя подборка")
    }
}
