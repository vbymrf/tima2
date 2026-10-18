package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.ChatPeople
import io.tima.domain.chat.ChatPerson
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.ReplyTarget
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `@Ник` в группе уходит ответом на последнее сообщение человека; «Ответить» важнее ника. */
class NickReplyInChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())
    private val sent = mutableListOf<String>()

    private val codec = object : MessageBodyCodec {
        override fun encodeText(text: String) = text.encodeToByteArray()
        override fun decodeText(body: ByteArray) = body.decodeToString()
        override fun encodeReply(text: String, marks: Boolean, reply: ReplyTarget) = "↩${reply.messageId}:${reply.userId}:$text".encodeToByteArray()
    }

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = ChatStore(
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        chatId = "g-1",
        observe = ObserveChat(ChatFeed { _, _ -> stream }),
        send = SendMessage(OutgoingQueue { _, _, body, _, _, _ -> sent += body.decodeToString(); true }, codec, DedupKeys { "d-${sent.size}" }),
        scope = scope,
        names = object : ChatPeople {
            override suspend fun person(userId: String) = ChatPerson(name = "Аня", nick = "anya")
        },
    )

    private fun line(id: Long) = ChatLine(
        dedupKey = "k-$id", chatId = "g-1", display = MessageDisplay.RECEIVED, text = "t$id",
        outgoing = false, atMs = id, localId = id, serverId = id, senderId = "u-anya",
    )

    @Test
    fun ник_ответом_на_последнее() = runTest {
        val s = store(backgroundScope)
        stream.value = listOf(line(12), line(11))
        runCurrent()
        s.draftChanged("@anya держи")
        s.sendPressed()
        assertEquals("↩12:u-anya:@anya держи", sent.single())
    }

    @Test
    fun ответить_важнее_ника() = runTest {
        val s = store(backgroundScope)
        stream.value = listOf(line(12), line(11))
        runCurrent()
        s.replyStarted(line(11))
        s.draftChanged("@anya держи")
        s.sendPressed()
        assertEquals("↩11:u-anya:@anya держи", sent.single())
    }
}
