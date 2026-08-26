package io.tima.feature.chat

import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.ChatSummary
import io.tima.domain.chat.ChatsFeed
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Окно переписок: единственное решение — различать «ещё не знаем» и «переписок нет».
 */
class ChatsStoreTest {

    private val stream = MutableStateFlow(emptyList<ChatSummary>())
    private var ordered: Int = -1

    private fun store(scope: kotlinx.coroutines.CoroutineScope, pageSize: Int = 50) = ChatsStore(
        observe = ObserveChats(ChatsFeed { limit -> ordered = limit; stream }),
        scope = scope,
        pageSize = pageSize,
    )

    /**
     * Пустой список **до** ответа базы не означает «переписок нет».
     *
     * Проверяется синхронно, до первой приостановки: сбор ещё не начался, и это ровно то
     * состояние, которое человек видит в первую долю секунды после запуска.
     */
    @Test
    fun пока_база_не_ответила_это_не_отсутствие_переписок() = runTest {
        val store = store(backgroundScope)

        assertFalse(store.state.value.read, "сбор ещё не начался — знать нечего")
        assertTrue(store.state.value.chats.isEmpty())

        // Ждём состояние, а не «прокрутку планировщика»: backgroundScope планировщик
        // работой не считает, и advanceUntilIdle объявил бы простой, ни разу не дав
        // корутине начаться. Эта ловушка в проекте уже стоила двух проверок.
        val after = store.state.first { it.read }

        assertTrue(after.chats.isEmpty(), "база ответила, и переписок правда нет")
    }

    @Test
    fun список_приходит_потоком_и_обновляется_сам() = runTest {
        val store = store(backgroundScope)
        store.state.first { it.read }

        stream.value = listOf(line("chat-1"), line("chat-2"))

        val after = store.state.first { it.chats.size == 2 }
        assertEquals(listOf("chat-1", "chat-2"), after.chats.map { it.chatId })
    }

    @Test
    fun размер_страницы_доезжает_до_запроса() = runTest {
        store(backgroundScope, pageSize = 7).state.first { it.read }

        assertEquals(7, ordered, "страницу просит Store, а не запрос сам по себе")
    }

    private fun line(chatId: String) = ChatSummary(
        chatId = chatId,
        title = "Аня",
        kind = ChatKind.Personal,
        peerId = "u-1",
        preview = "привет",
        lastOutgoing = false,
        lastDisplay = MessageDisplay.RECEIVED,
        atMs = 1_000,
        unread = 0,
    )
}
