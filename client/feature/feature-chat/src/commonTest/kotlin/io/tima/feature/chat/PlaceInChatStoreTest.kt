package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ChatPlaces
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** С чего открыть переписку (ПЛАН-(МП) М2): первое непрочитанное → где остановились → низ; место пишется при выходе. */
class PlaceInChatStoreTest {

    private val total = 120L
    private var unread = 0L
    private val saved = mutableMapOf<String, Long>()

    private val feed = object : ChatFeed {
        override fun page(chatId: String, limit: Int): Flow<List<ChatLine>> =
            flowOf((total downTo maxOf(1, total - limit + 1)).map { line(it) })

        override suspend fun depth(chatId: String, serverId: Long): Int = (total - serverId + 1).toInt()

        override suspend fun firstUnread(chatId: String): Long = unread
    }

    private val places = object : ChatPlaces {
        override suspend fun load(chatId: String): Long = saved[chatId] ?: 0L
        override suspend fun save(chatId: String, serverId: Long) {
            saved[chatId] = serverId
        }
    }

    private fun line(id: Long) = ChatLine(
        dedupKey = "d-$id", chatId = "chat-1", display = MessageDisplay.RECEIVED,
        outgoing = false, atMs = id * 1_000, localId = id, serverId = id,
    )

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = ChatStore(
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        chatId = "chat-1",
        observe = ObserveChat(feed),
        send = SendMessage(
            queue = OutgoingQueue { _, _, _, _, _, _ -> true },
            codec = object : MessageBodyCodec {
                override fun encodeText(text: String) = ByteArray(1)
                override fun decodeText(body: ByteArray): String? = null
            },
            keys = DedupKeys { "k" },
            maxBodyBytes = 100,
        ),
        scope = scope,
        places = places,
    )

    @Test
    fun непрочитанное_главнее_сохранённого_и_лента_до_него_дорастает() = runTest {
        unread = 30
        saved["chat-1"] = 100
        val sc = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val s = store(sc)
        advanceUntilIdle()
        assertEquals(30L, s.state.value.openAt)
        assertEquals(30L, s.state.value.firstUnread)
        assertTrue(s.state.value.lines.any { it.serverId == 30L }, "старше 50 последних — лента дорощена")
        sc.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test
    fun нет_непрочитанного_где_остановились() = runTest {
        saved["chat-1"] = 100
        val sc = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val s = store(sc)
        advanceUntilIdle()
        assertEquals(100L, s.state.value.openAt)
        assertEquals(0L, s.state.value.firstUnread)
        sc.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test
    fun место_пишется_при_выходе() = runTest {
        val sc = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val s = store(sc)
        advanceUntilIdle()
        assertEquals(0L, s.state.value.openAt, "ничего нет — внизу")
        s.savePlace(77)
        advanceUntilIdle()
        assertEquals(77L, saved["chat-1"])
        sc.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }
}
