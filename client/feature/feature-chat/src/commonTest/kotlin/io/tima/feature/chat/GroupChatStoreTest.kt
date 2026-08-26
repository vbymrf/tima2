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
class ГрупповаяПерепискаStoreTest {

    private val поток = MutableStateFlow<List<ChatLine>>(emptyList())

    @Test
    fun имя_автора_спрашивается_по_разу_на_человека() = runTest {
        // Список обновляется на каждое пришедшее сообщение. Спрашивай мы имя на каждой
        // строке — получили бы запрос к серверу на каждую реплику в группе.
        val справочник = СчитающийСправочник()
        val store = store(backgroundScope, справочник)

        поток.value = listOf(строка("m1", "u-2"), строка("m2", "u-2"), строка("m3", "u-3"))
        runCurrent()

        assertEquals(mapOf("u-2" to "Имя u-2", "u-3" to "Имя u-3"), store.state.value.имена)
        assertEquals(listOf("u-2", "u-3"), справочник.спрошены)

        // Пришло ещё одно от того же человека — второго запроса быть не должно.
        поток.value = поток.value + строка("m4", "u-2")
        runCurrent()
        assertEquals(listOf("u-2", "u-3"), справочник.спрошены)
    }

    @Test
    fun личная_переписка_за_именами_не_ходит() = runTest {
        val store = store(backgroundScope, справочник = null)
        поток.value = listOf(строка("m1", "u-2"))
        runCurrent()

        assertFalse(store.state.value.группа)
        assertTrue(store.state.value.имена.isEmpty())
    }

    @Test
    fun групповая_переписка_помечена_как_групповая() = runTest {
        // По этому признаку экран решает, показывать ли автора у каждой реплики.
        val store = store(backgroundScope, СчитающийСправочник())
        assertTrue(store.state.value.группа)
    }

    private fun строка(ключ: String, автор: String?) = ChatLine(
        dedupKey = ключ,
        chatId = "g-1",
        display = MessageDisplay.RECEIVED,
        text = "привет",
        outgoing = false,
        atMs = 1_000,
        localId = 1,
        senderId = автор,
    )

    private fun store(scope: kotlinx.coroutines.CoroutineScope, справочник: ChatNames?) = ChatStore(
        chatId = "g-1",
        observe = ObserveChat(ChatFeed { _, _ -> поток }),
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
        names = справочник,
    )

    private class СчитающийСправочник : ChatNames {
        val спрошены = mutableListOf<String>()
        override suspend fun имя(userId: String): String {
            спрошены += userId
            return "Имя $userId"
        }
    }
}
