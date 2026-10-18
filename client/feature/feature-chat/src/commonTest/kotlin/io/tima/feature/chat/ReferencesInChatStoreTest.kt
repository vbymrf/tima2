package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.FoundMessage
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.MessageFinder
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.RichBlock
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Вставленное и подкладка из другой переписки, ответ на давнее (Решения §11.3–§11.4): чего нет в
 * ленте — спрашивается по номеру один раз; переход к нему показывает сообщение одним.
 */
class ReferencesInChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())
    private val asked = mutableListOf<String>()
    private val logged = mutableListOf<String>()

    private val codec = object : MessageBodyCodec {
        override fun encodeText(text: String) = text.encodeToByteArray()
        override fun decodeText(body: ByteArray) = body.decodeToString()
    }

    private val finder = MessageFinder { kind, chat, number ->
        asked += "$kind:$chat:$number"
        if (number == 5L) FoundMessage.Unavailable("level", 3)
        else FoundMessage.Found(ChatLine(dedupKey = "r", chatId = chat, display = MessageDisplay.RECEIVED, text = "там", outgoing = false, atMs = 0, localId = 0, serverId = number))
    }

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = ChatStore(
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        chatId = "chat-1",
        observe = ObserveChat(ChatFeed { _, _ -> stream }),
        send = SendMessage(OutgoingQueue { _, _, _, _, _, _ -> true }, codec, DedupKeys { "d" }),
        scope = scope,
        finder = finder,
    ).also { it.refLog = { kind, number, reason, level -> logged += "$kind:$number:$reason:$level" } }

    private fun line(id: Long, vararg blocks: RichBlock) = ChatLine(
        dedupKey = "k-$id", chatId = "chat-1", display = MessageDisplay.RECEIVED, text = "t$id",
        outgoing = false, atMs = id, localId = id, serverId = id, rich = RichContent(blocks.toList()),
    )

    @Test
    fun чего_нет_в_ленте_спрашивается_один_раз() = runTest {
        val s = store(backgroundScope)
        stream.value = listOf(line(2, RichBlock.Quoted("group", "g-9", 7, author = false, cut = "cut")), line(1))
        runCurrent()
        stream.value = stream.value.toList()
        runCurrent()
        assertEquals(listOf("group:g-9:7"), asked, "вставленное из другой переписки — спрошено один раз")
        assertIs<FoundMessage.Found>(s.state.value.found["g-9/7"])
    }

    @Test
    fun то_что_в_ленте_не_спрашивается() = runTest {
        store(backgroundScope)
        stream.value = listOf(line(2, RichBlock.Underlay("personal", "chat-1", 1, author = false, lines = 3)), line(1))
        runCurrent()
        assertEquals(emptyList(), asked)
    }

    @Test
    fun недоступное_в_журнал_и_переход_показывает_одним() = runTest {
        val s = store(backgroundScope)
        stream.value = listOf(line(2, RichBlock.Underlay("group", "g-9", 5, author = false, lines = 3)))
        runCurrent()
        assertEquals(listOf("group:5:level:3"), logged)
        s.peek("group", "g-9", 5)
        assertIs<FoundMessage.Unavailable>(s.state.value.peek)
        s.peekClosed()
        s.peek("group", "g-9", 8)
        runCurrent()
        assertEquals("там", (s.state.value.peek as FoundMessage.Found).line.text)
    }
}
