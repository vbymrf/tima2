package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ChatNames
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Групповая переписка глазами экрана: кто написал.
 *
 * В личной собеседник один и назван в шапке; в группе без автора реплика теряет половину
 * смысла. Отсюда две проверки: имена спрашиваются ровно по разу на человека, и личная
 * переписка за ними не ходит вовсе.
 */
class GroupChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())

    @Test
    fun имя_автора_спрашивается_по_разу_на_человека() = runTest {
        // Список обновляется на каждое пришедшее сообщение. Спрашивай мы имя на каждой
        // строке — получили бы запрос к серверу на каждую реплику в группе.
        val directory = DirectoryCounting()
        val store = store(backgroundScope, directory)

        stream.value = listOf(line("m1", "u-2"), line("m2", "u-2"), line("m3", "u-3"))
        runCurrent()

        assertEquals(mapOf("u-2" to "Имя u-2", "u-3" to "Имя u-3"), store.state.value.names)
        assertEquals(listOf("u-2", "u-3"), directory.asked)

        // Пришло ещё одно от того же человека — второго запроса быть не должно.
        stream.value = stream.value + line("m4", "u-2")
        runCurrent()
        assertEquals(listOf("u-2", "u-3"), directory.asked)
    }

    @Test
    fun личная_переписка_за_именами_не_ходит() = runTest {
        val store = store(backgroundScope, directory = null)
        stream.value = listOf(line("m1", "u-2"))
        runCurrent()

        assertFalse(store.state.value.group)
        assertTrue(store.state.value.names.isEmpty())
    }

    @Test
    fun групповая_переписка_помечена_как_групповая() = runTest {
        // По этому признаку экран решает, показывать ли автора у каждой реплики.
        val store = store(backgroundScope, DirectoryCounting())
        assertTrue(store.state.value.group)
    }

    private fun line(key: String, author: String?) = ChatLine(
        dedupKey = key,
        chatId = "g-1",
        display = MessageDisplay.RECEIVED,
        text = "привет",
        outgoing = false,
        atMs = 1_000,
        localId = 1,
        senderId = author,
    )

    private fun store(scope: kotlinx.coroutines.CoroutineScope, directory: ChatNames?) = ChatStore(
        chatId = "g-1",
        observe = ObserveChat(ChatFeed { _, _ -> stream }),
        send = SendMessage(
            queue = OutgoingQueue { _, _, _ -> true },
            codec = object : MessageBodyCodec {
                override fun encodeText(text: String) = ByteArray(10)
                override fun decodeText(body: ByteArray): String? = null
            },
            keys = DedupKeys { "d-1" },
            maxBodyBytes = 100,
        ),
        scope = scope,
        names = directory,
    )

    private class DirectoryCounting : ChatNames {
        val asked = mutableListOf<String>()
        override suspend fun name(userId: String): String {
            asked += userId
            return "Имя $userId"
        }
    }
}
