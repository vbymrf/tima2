package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ветка в группе (ПЛАН-КАНАЛОВ К6, ADR-0024).
 *
 * Ветка — то же самое, что комментарий, только внутри шифра: сообщение, у которого
 * контейнер не группа, а другое сообщение. Проверяется ровно то, что план назвал
 * проверяемым: в общем списке ветка свёрнута в строку, а ответ уходит тем же путём.
 */
class ThreadInChatStoreTest {

    private class Enqueued(
        val chatId: String,
        val level: Int,
        val threadRoot: Long,
    )

    private fun store(
        lines: List<ChatLine>,
        sent: MutableList<Enqueued>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): ChatStore = ChatStore(
        chatId = "g-1",
        observe = ObserveChat(
            object : ChatFeed {
                override fun page(chatId: String, limit: Int): Flow<List<ChatLine>> = flowOf(lines)
            },
        ),
        send = SendMessage(
            queue = OutgoingQueue { _, chatId, _, level, threadRoot ->
                sent += Enqueued(chatId, level, threadRoot)
                true
            },
            codec = object : MessageBodyCodec {
                override fun encodeText(text: String): ByteArray = text.encodeToByteArray()
                override fun decodeText(body: ByteArray): String = body.decodeToString()
            },
            keys = { "k-${sent.size + 1}" },
        ),
        scope = scope,
    )

    private fun line(
        serverId: Long,
        text: String,
        threadRoot: Long = 0,
        level: Int = 2,
    ) = ChatLine(
        dedupKey = "d-$serverId",
        chatId = "g-1",
        display = MessageDisplay.SENT,
        text = text,
        outgoing = false,
        atMs = serverId,
        localId = serverId,
        serverId = serverId,
        senderId = "u-1",
        level = level,
        threadRoot = threadRoot,
    )

    @Test
    fun ветка_свёрнута_в_строку_а_не_показана_дважды() = runTest {
        val sent = mutableListOf<Enqueued>()
        val store = store(
            listOf(line(1, "исходное"), line(2, "ответ", threadRoot = 1), line(3, "ещё ответ", threadRoot = 1)),
            sent,
            this,
        )
        runCurrent()

        val lines = store.state.value.lines
        assertEquals(1, lines.size, "ответы обязаны исчезнуть из общего списка: $lines")
        assertEquals(2, lines.first().replies, "под корнем — число ответов")
    }

    @Test
    fun открытая_ветка_показывает_корень_и_ответы() = runTest {
        val sent = mutableListOf<Enqueued>()
        val store = store(listOf(line(1, "исходное"), line(2, "ответ", threadRoot = 1)), sent, this)
        runCurrent()

        store.threadOpened(1)
        runCurrent()

        val thread = store.state.value.thread
        assertTrue(thread != null, "ветка обязана открыться")
        assertEquals("исходное", thread.root.text, "первой строкой — исходное сообщение целиком")
        assertEquals(listOf("ответ"), thread.replies.map { it.text })
    }

    @Test
    fun ответ_в_ветке_уходит_тем_же_путём_и_с_кругом_корня() = runTest {
        val sent = mutableListOf<Enqueued>()
        val store = store(listOf(line(1, "исходное", level = 2), line(2, "ответ", threadRoot = 1)), sent, this)
        runCurrent()

        store.threadOpened(1)
        store.threadDraftChanged("мой ответ")
        store.threadSendPressed()
        runCurrent()

        assertEquals(1, sent.size, "ответ обязан уйти обычной отправкой, а не второй ручкой")
        assertEquals("g-1", sent.single().chatId, "тот же чат — значит тот же ключ группы")
        assertEquals(1L, sent.single().threadRoot, "корень назван")
        // Круг у ответа не свой: он берётся у корня — сервер отдаёт ветку тем же, кому
        // отдал корень.
        assertEquals(2, sent.single().level)
        assertEquals("", store.state.value.threadDraft, "поле ветки очищается после отправки")
    }

    @Test
    fun закрытая_ветка_не_держит_черновик() = runTest {
        val sent = mutableListOf<Enqueued>()
        val store = store(listOf(line(1, "исходное"), line(2, "ответ", threadRoot = 1)), sent, this)
        runCurrent()

        store.threadOpened(1)
        store.threadDraftChanged("не отправлю")
        store.threadClosed()

        assertNull(store.state.value.thread)
        assertEquals("", store.state.value.threadDraft, "закрытая ветка не хранит набранное")
    }
}
