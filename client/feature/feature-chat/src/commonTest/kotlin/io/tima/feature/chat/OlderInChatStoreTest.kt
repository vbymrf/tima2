package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Лента растёт (заказчик 2026-11-09: старые сообщения не видны, указатели на них молчат): дошли до верха —
 * ещё порция; переход к старому — лента дорастает до него; старше нет — не растёт.
 */
class OlderInChatStoreTest {

    /** В переписке 230 сообщений, номера 1…230; лента — последние `limit`. */
    private val total = 230L
    private val asked = mutableListOf<Int>()

    private val feed = object : ChatFeed {
        override fun page(chatId: String, limit: Int): Flow<List<ChatLine>> {
            asked += limit
            return flowOf((total downTo maxOf(1, total - limit + 1)).map(::line))
        }

        override suspend fun depth(chatId: String, serverId: Long): Int =
            if (serverId in 1..total) (total - serverId + 1).toInt() else 0
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
    )

    @Test
    fun дошли_до_верха_ещё_порция_а_старше_нет_не_растёт() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val store = store(scope)
        advanceUntilIdle()
        assertEquals(50, store.state.value.lines.size)
        store.older()
        advanceUntilIdle()
        assertEquals(100, store.state.value.lines.size)
        repeat(5) { store.older(); advanceUntilIdle() }
        assertEquals(230, store.state.value.lines.size, "вся переписка")
        val before = asked.size
        store.older()
        advanceUntilIdle()
        assertEquals(before, asked.size, "старше нет — база не спрашивается")
        scope.cancel()
    }

    @Test
    fun переход_к_старому_доращивает_ленту_до_него() = runTest {
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.Job())
        val store = store(scope)
        advanceUntilIdle()
        assertFalse(store.state.value.lines.any { it.serverId == 20L })
        assertTrue(store.reach(20))
        advanceUntilIdle()
        assertTrue(store.state.value.lines.any { it.serverId == 20L }, "старое пришло в ленту")
        assertFalse(store.reach(999), "не из этой переписки — одно окно")
        scope.cancel()
    }
}

private fun kotlinx.coroutines.CoroutineScope.cancel() = (coroutineContext[kotlinx.coroutines.Job]!!).cancel()
