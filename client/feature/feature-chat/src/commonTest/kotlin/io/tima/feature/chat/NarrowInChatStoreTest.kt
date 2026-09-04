package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageLevels
import io.tima.domain.chat.NarrowMessageLevel
import io.tima.domain.chat.NarrowStep
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сужение круга глазами экрана (ПЛАН-СОЦИУМА Г7).
 *
 * Проверяется порядок: **сначала предупреждение, потом действие**. Сужение необратимо и
 * неполно — у тех, кто уже унёс сообщение к себе, оно останется, — и узнать об этом
 * человек обязан до нажатия, а не после.
 */
class NarrowInChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())
    private val feed = ChatFeed { _, _ -> stream }

    @Test
    fun предупреждение_идёт_раньше_запроса() = runTest {
        val port = CountingLevels()
        val store = store(backgroundScope, port)

        store.narrowAsked(messageId = 7, was = 1, to = 3)
        runCurrent()

        val notice = assertIs<ChatNotice.NarrowWarning>(store.state.value.notice)
        assertEquals("По разрешению", notice.circle)
        assertEquals(0, port.calls, "запрос ушёл до того, как человек согласился")
        assertTrue(store.state.value.pendingNarrow != null, "задуманное сужение потерялось")
    }

    @Test
    fun подтверждение_отправляет_запрос() = runTest {
        val port = CountingLevels()
        val store = store(backgroundScope, port)

        store.narrowAsked(messageId = 7, was = 1, to = 3)
        store.narrowConfirmed()
        runCurrent()

        assertEquals(1, port.calls)
        assertEquals(3, port.lastLevel)
        assertIs<ChatNotice.Narrowed>(store.state.value.notice)
        assertNull(store.state.value.pendingNarrow, "задуманное сужение осталось висеть после отправки")
    }

    @Test
    fun отказ_от_сужения_ничего_не_шлёт() = runTest {
        val port = CountingLevels()
        val store = store(backgroundScope, port)

        store.narrowAsked(messageId = 7, was = 1, to = 3)
        store.narrowDropped()
        store.narrowConfirmed()
        runCurrent()

        assertEquals(0, port.calls, "передумавший человек всё равно сузил сообщение")
        assertNull(store.state.value.notice)
    }

    @Test
    fun в_личной_переписке_сужать_нечего() = runTest {
        // `narrow = null` — переписка личная: круга у сообщения нет, и нажатие не должно
        // приводить ни к запросу, ни к предупреждению о том, чего не будет.
        val store = ChatStore(
            chatId = "chat-1",
            observe = ObserveChat(feed),
            send = send(),
            scope = backgroundScope,
        )

        store.narrowAsked(messageId = 7, was = 1, to = 3)
        runCurrent()

        assertNull(store.state.value.notice)
        assertNull(store.state.value.pendingNarrow)
    }

    private fun store(scope: kotlinx.coroutines.CoroutineScope, port: MessageLevels) = ChatStore(
        chatId = "g-1",
        observe = ObserveChat(feed),
        send = send(),
        scope = scope,
        narrow = NarrowMessageLevel(port),
    )

    private fun send() = SendMessage(
        queue = OutgoingQueue { _, _, _, _ -> true },
        codec = object : MessageBodyCodec {
            override fun encodeText(text: String) = ByteArray(1)
            override fun decodeText(body: ByteArray): String? = null
        },
        keys = DedupKeys { "d-1" },
    )

    private class CountingLevels : MessageLevels {
        var calls = 0
            private set
        var lastLevel = Int.MIN_VALUE
            private set

        override suspend fun narrow(groupId: String, messageId: Long, level: Int): NarrowStep {
            calls++
            lastLevel = level
            return NarrowStep.Narrowed(level)
        }
    }
}
