package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ChatPeople
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.ChatPins
import io.tima.domain.chat.ComposedBodies
import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.PinStep
import io.tima.domain.chat.RemotePins
import io.tima.domain.chat.ReplyTarget
import io.tima.domain.chat.RichContent
import io.tima.domain.chat.SendComposed
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Закреплённое и описание в переписке группы (ПЛАН-(ЗО) ЗО3–ЗО5): уровень 0 в ленту не идёт, но
 * подкладке виден; закрепление — через сервер, отказ словами; суперсообщение ставится по номеру,
 * когда его сообщение получило номер от сервера.
 */
class PinsAndShowcaseInChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())
    private val sent = mutableListOf<Pair<String, Int>>()
    private val calls = mutableListOf<String>()
    private var refuse: String? = null
    private val supers = mutableListOf<Long>()

    private val codec = object : MessageBodyCodec {
        override fun encodeText(text: String) = text.encodeToByteArray()
        override fun decodeText(body: ByteArray) = body.decodeToString()
    }

    private val bodies = object : ComposedBodies {
        override fun problems(doc: ComposedDoc) = emptyList<String>()
        override fun encode(doc: ComposedDoc, reply: ReplyTarget?) = "супер".encodeToByteArray()
        override fun preview(doc: ComposedDoc): RichContent? = null
        override fun save(doc: ComposedDoc) = ""
        override fun load(saved: String) = ComposedDoc()
    }

    private val remote = object : RemotePins {
        override suspend fun read(chatId: String, group: Boolean): Long = 7
        override suspend fun set(chatId: String, group: Boolean, serverId: Long): PinStep {
            calls += "pin:$serverId"
            return refuse?.let { PinStep.Refused(it) } ?: PinStep.Done
        }
        override suspend fun clear(chatId: String, group: Boolean): PinStep {
            calls += "unpin"
            return PinStep.Done
        }
    }

    private val queue = OutgoingQueue { _, _, body, level, _, _ -> sent += body.decodeToString() to level; true }

    private fun store(scope: kotlinx.coroutines.CoroutineScope, pins: ChatPins = ChatPins(remote)) = ChatStore(
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        chatId = "g-1",
        observe = ObserveChat(ChatFeed { _, _ -> stream }),
        send = SendMessage(queue, codec, DedupKeys { "d-${sent.size}" }),
        scope = scope,
        names = ChatPeople { ChatPerson(name = "Аня") },
        pins = pins,
        pinMay = true,
        setSuper = { n -> supers += n; true },
        composer = SendComposed(queue = queue, bodies = bodies, keys = DedupKeys { "s-1" }),
    )

    private fun line(id: Long, level: Int, key: String = "k-$id") = ChatLine(
        dedupKey = key, chatId = "g-1", display = MessageDisplay.RECEIVED, text = "t$id",
        outgoing = false, atMs = id, localId = id, serverId = id, senderId = "u-1", level = level,
    )

    @Test
    fun уровень_0_в_ленту_не_идёт_но_подкладке_виден() = runTest {
        val s = store(backgroundScope)
        stream.value = listOf(line(3, 1), line(2, 0), line(1, -1))
        runCurrent()
        assertEquals(listOf(3L, 1L), s.state.value.lines.map { it.serverId })
        assertTrue(s.state.value.continuations.any { it.serverId == 2L })
    }

    @Test
    fun закреплённое_читается_при_открытии_и_меняется_через_сервер() = runTest {
        val s = store(backgroundScope)
        runCurrent()
        assertEquals(7L, s.state.value.pinned, "номер с сервера при открытии")
        s.pin(line(3, 1))
        runCurrent()
        assertEquals(listOf("pin:3"), calls)
        assertEquals(3L, s.state.value.pinned)
        s.unpin()
        runCurrent()
        assertEquals(0L, s.state.value.pinned)
    }

    @Test
    fun отказ_сервера_словами_и_закреплённое_прежнее() = runTest {
        refuse = "not_moderator"
        val s = store(backgroundScope)
        runCurrent()
        s.pin(line(3, 1))
        runCurrent()
        assertEquals(7L, s.state.value.pinned)
        assertIs<ChatNotice.Said>(s.state.value.notice)
    }

    @Test
    fun удалённое_закреплённое_открепляется_событием() = runTest {
        val pins = ChatPins(remote)
        val s = store(backgroundScope, pins)
        runCurrent()
        pins.deleted("g-1", 7)
        runCurrent()
        assertEquals(0L, s.state.value.pinned)
    }

    @Test
    fun суперсообщение_ставится_когда_пришёл_номер() = runTest {
        val s = store(backgroundScope)
        runCurrent()
        s.composeSuper(ComposedDoc(listOf(ComposedItem.Text("описание")), level = 0))
        assertTrue(s.state.value.editorOpen)
        assertEquals(0, s.state.value.level, "суперсообщение — уровень 0")
        s.sendComposed(s.state.value.composed!!)
        assertEquals(listOf("супер" to 0), sent)
        assertEquals(emptyList(), supers, "номера ещё нет — ставить нечего")
        stream.value = listOf(line(9, 0, key = "s-1"))
        runCurrent()
        assertEquals(listOf(9L), supers)
    }
}
