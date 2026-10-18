package io.tima.core.database

import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.IncomingState
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.ObserveChat
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Кеш разбора ленты (ADR-0011 §10, Решения §14): пришло одно сообщение — разбирается одно, а не
 * вся страница заново.
 */
class DecodedCacheTest {

    private var decodes = 0
    private val counting = object : MessageBodyCodec {
        override fun encodeText(text: String) = text.encodeToByteArray()
        override fun decodeText(body: ByteArray): String { decodes++; return body.decodeToString() }
    }

    private val db = testDatabase()
    private val cipher = testCipher()
    private val inbox = SqlInboxStore(db, cipher)

    private fun incoming(id: Long, text: String) {
        inbox.putIfAbsent(IncomingEntry(chatId = "c", messageId = id, envelope = byteArrayOf(9), state = IncomingState.RECEIVED, receivedAtMs = id, sentAtMs = id))
        inbox.storeParsed("c", id, text.encodeToByteArray(), "u-он", -1, 0)
        inbox.update(inbox.byKey("c", id)!!.copy(state = IncomingState.STORED))
    }

    @Test
    fun новое_сообщение_не_пересобирает_старые() = runTest {
        incoming(1, "раз")
        incoming(2, "два")
        val pages = mutableListOf<Int>()
        val job = launch {
            ObserveChat(SqlChatFeed(db, counting, cipher, "u-я")).page("c").take(2).toList().forEach { pages += it.size }
        }
        while (pages.isEmpty() && decodes < 2) yield()
        kotlinx.coroutines.delay(50)
        incoming(3, "три")
        job.join()
        assertEquals(listOf(2, 3), pages)
        assertEquals(3, decodes, "разобрано заново больше, чем пришло")
    }
}
