package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.DeleteMessage
import io.tima.domain.chat.DeleteStep
import io.tima.domain.chat.EditBody
import io.tima.domain.chat.EditMessage
import io.tima.domain.chat.EditOriginal
import io.tima.domain.chat.EditQueue
import io.tima.domain.chat.LocalDelete
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.MessagePicks
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.PickStore
import io.tima.domain.chat.RemoteDelete
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Меню сообщения в окне переписки (Решения §11.1): «Изменить» уводит отправку в правку,
 * «Удалить у всех» спрашивает и только потом зовёт сервер, «Удалить у себя» — без вопроса,
 * «Добавить в Подборку» — ссылка на устройстве.
 */
class MessageMenuInChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())
    private val sent = mutableListOf<String>()
    private val edits = mutableListOf<Pair<Long, String>>()
    private val removed = mutableListOf<String>()
    private val remote = mutableListOf<Long>()
    private val picks = mutableSetOf<Triple<String, String, Long>>()

    private val codec = object : MessageBodyCodec {
        override fun encodeText(text: String) = text.encodeToByteArray()
        override fun decodeText(body: ByteArray) = body.decodeToString()
        override fun editSource(body: ByteArray, edits: List<ByteArray>) = body.decodeToString()
        override fun encodeEdit(body: ByteArray, edits: List<ByteArray>, typed: String, marks: Boolean) =
            if (typed == body.decodeToString()) EditBody.Same else EditBody.Changes(typed.encodeToByteArray())
    }

    /** Ответ помечается в теле — так видно, что отправка ушла ответом. */
    private val replyCodec = object : MessageBodyCodec by codec {
        override fun encodeReply(text: String, marks: Boolean, reply: io.tima.domain.chat.ReplyTarget) =
            "↩${reply.messageId}:$text".encodeToByteArray()
    }

    /** Ряд знаками не выразить: строке ввода — ничего, редактору — документ из двух кусков. */
    private val rowCodec = object : MessageBodyCodec by codec {
        override fun editSource(body: ByteArray, edits: List<ByteArray>): String? = null
        override fun editDoc(body: ByteArray, edits: List<ByteArray>) =
            io.tima.domain.chat.ComposedDoc(listOf(io.tima.domain.chat.ComposedItem.Text("было"), io.tima.domain.chat.ComposedItem.Widget(io.tima.domain.chat.ComposedWidget.Emoji("🎉"))))
        override fun encodeEditDoc(body: ByteArray, edits: List<ByteArray>, doc: io.tima.domain.chat.ComposedDoc): EditBody = when {
            doc.items.size != 2 -> EditBody.Structure
            (doc.items[0] as io.tima.domain.chat.ComposedItem.Text).source == "было" -> EditBody.Same
            else -> EditBody.Changes((doc.items[0] as io.tima.domain.chat.ComposedItem.Text).source.encodeToByteArray())
        }
    }

    private fun store(scope: kotlinx.coroutines.CoroutineScope, editCodec: MessageBodyCodec = codec) = ChatStore(
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        chatId = "chat-1",
        observe = ObserveChat(ChatFeed { _, _ -> stream }),
        send = SendMessage(OutgoingQueue { key, _, body, _, _, _ -> sent += body.decodeToString(); true }, replyCodec, DedupKeys { "d-${sent.size}" }),
        scope = scope,
        edit = EditMessage(
            queue = EditQueue { _, _, body, _, editOf -> edits += editOf to body.decodeToString(); true },
            bodies = { _, _ -> EditOriginal("было".encodeToByteArray(), emptyList(), -1, own = true) },
            codec = editCodec,
            keys = DedupKeys { "e-1" },
        ),
        deleter = DeleteMessage(
            object : LocalDelete {
                override fun forMe(chatId: String, dedupKey: String, serverId: Long) { removed += dedupKey }
                override fun markDeleted(chatId: String, serverId: Long) { removed += "all:$serverId" }
            },
            RemoteDelete { _, serverId, _ -> remote += serverId; DeleteStep.Done },
        ),
        picks = MessagePicks(
            object : PickStore {
                override fun has(level: String, chatId: String, serverId: Long) = Triple(level, chatId, serverId) in picks
                override fun count(level: String) = picks.count { it.first == level }
                override fun add(level: String, chatId: String, serverId: Long, atMs: Long) { picks += Triple(level, chatId, serverId) }
            },
            nowMs = { 0 },
        ),
    )

    private val mine = ChatLine(
        dedupKey = "d-42", chatId = "chat-1", display = MessageDisplay.SENT, text = "было",
        outgoing = true, atMs = 1_000, localId = 1, serverId = 42,
    )

    @Test
    fun изменить_кладёт_текст_в_строку_и_отправка_уходит_правкой() = runTest {
        val store = store(backgroundScope)
        store.editStarted(mine)
        runCurrent()
        assertEquals("было", store.state.value.draft)
        assertEquals(42L, store.state.value.editing)
        store.draftChanged("стало")
        store.sendPressed()
        assertEquals(listOf(42L to "стало"), edits)
        assertEquals(emptyList(), sent, "правка не уходит новым сообщением")
        assertEquals(0L, store.state.value.editing)
        assertEquals("", store.state.value.draft)
    }

    /** Заказчик 2026-10-18: «При попытке изменить сообщение — пишет "правится в редакторе". Редактор не открывает». */
    @Test
    fun изменить_ряд_открывает_редактор_и_уходит_правкой() = runTest {
        val store = store(backgroundScope, rowCodec)
        store.editStarted(mine)
        runCurrent()
        val s = store.state.value
        assertEquals(true, s.editorOpen, "редактор открыт")
        assertEquals(2, s.editDoc!!.items.size)
        assertEquals(42L, s.editing)

        val grown = s.editDoc!!.copy(items = s.editDoc!!.items + io.tima.domain.chat.ComposedItem.Text("ещё"))
        assertIs<io.tima.domain.chat.EditResult.Structure>(store.editorEditSend(grown))
        assertEquals(true, store.state.value.editorOpen, "строение изменилось — окно остаётся, причину скажет редактор")

        val changed = io.tima.domain.chat.ComposedDoc(listOf(io.tima.domain.chat.ComposedItem.Text("стало"), s.editDoc!!.items[1]))
        assertIs<io.tima.domain.chat.EditResult.Queued>(store.editorEditSend(changed))
        assertEquals(listOf(42L to "стало"), edits)
        assertEquals(emptyList(), sent, "правка не уходит новым сообщением")
        assertEquals(false, store.state.value.editorOpen)
        assertEquals(0L, store.state.value.editing)
        assertEquals(null, store.state.value.composed, "правка черновиком не становится")
    }

    @Test
    fun отмена_правки_в_редакторе() = runTest {
        val store = store(backgroundScope, rowCodec)
        store.editStarted(mine)
        runCurrent()
        store.editorDraft(store.state.value.editDoc!!)
        store.editorEditCancelled()
        assertEquals(false, store.state.value.editorOpen)
        assertEquals(0L, store.state.value.editing)
        assertEquals(null, store.state.value.composed)
        assertEquals(emptyList(), edits)
    }

    /** Заказчик 2026-10-19: выбор нескольких — «Удалить у всех» спрашивает один раз и удаляет все. */
    @Test
    fun выбранные_удаляются_у_всех_после_одного_вопроса() = runTest {
        val store = store(backgroundScope)
        val second = mine.copy(dedupKey = "d-43", serverId = 43)
        val foreign = mine.copy(dedupKey = "d-44", serverId = 44, outgoing = false)
        store.deleteForAllManyAsked(listOf(mine, second, foreign))
        val ask = assertIs<ChatNotice.DeleteAsk>(store.state.value.notice)
        assertEquals(listOf(42L, 43L), listOf(ask.serverId) + ask.more, "чужое у всех не удаляется")
        assertEquals(emptyList(), remote, "до подтверждения сервер не зовётся")
        store.deleteForAllConfirmed()
        runCurrent()
        assertEquals(listOf(42L, 43L), remote)
        store.deleteForMeMany(listOf(foreign))
        runCurrent()
        assertEquals(true, "d-44" in removed, "у себя — и чужое")
    }

    /** Переслать: сообщение — знаками, как набрано; в другой переписке уходит отдельным сообщением. */
    @Test
    fun пересланное_уходит_отдельными_сообщениями() = runTest {
        val store = store(backgroundScope)
        assertEquals(Forwarded.Text("было"), store.forwardOf(mine))
        store.sendForwarded(listOf(Forwarded.Text("раз"), Forwarded.Text("два")))
        assertEquals(listOf("раз", "два"), sent)
        assertEquals(null, store.forwardOf(mine.copy(deleted = true)), "удалённое не пересылается")
    }

    @Test
    fun отмена_правки_возвращает_обычную_отправку() = runTest {
        val store = store(backgroundScope)
        store.editStarted(mine)
        runCurrent()
        store.editCancelled()
        store.draftChanged("новое")
        store.sendPressed()
        assertEquals(listOf("новое"), sent)
        assertEquals(emptyList(), edits)
    }

    @Test
    fun удалить_у_всех_только_после_подтверждения() = runTest {
        val store = store(backgroundScope)
        store.deleteForAllAsked(mine)
        assertIs<ChatNotice.DeleteAsk>(store.state.value.notice)
        assertEquals(emptyList(), remote, "до подтверждения сервер не зовётся")
        store.deleteForAllConfirmed()
        runCurrent()
        assertEquals(listOf(42L), remote)
        assertEquals(listOf("all:42"), removed)
    }

    @Test
    fun удалить_у_себя_без_вопроса() = runTest {
        val store = store(backgroundScope)
        store.deleteForMe(mine)
        runCurrent()
        assertEquals(listOf("d-42"), removed)
        assertEquals(emptyList(), remote)
    }

    @Test
    fun ответить_уходит_ответом_и_полоса_уходит() = runTest {
        val store = store(backgroundScope)
        val theirs = mine.copy(outgoing = false, senderId = "u-он", serverId = 7)
        store.replyStarted(theirs)
        assertEquals(7L, store.state.value.replyTo?.serverId)
        store.draftChanged("да")
        store.sendPressed()
        assertEquals(listOf("↩7:да"), sent)
        assertEquals(null, store.state.value.replyTo)
    }

    @Test
    fun подборка_по_одной_ссылке() = runTest {
        val store = store(backgroundScope)
        store.picked(mine)
        runCurrent()
        store.picked(mine)
        runCurrent()
        assertEquals(1, picks.size)
        assertIs<ChatNotice.Said>(store.state.value.notice)
    }
}
