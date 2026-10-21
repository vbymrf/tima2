package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Сначала вид группы, потом ключ (заказчик 2026-10-21: «мы сначала относимся к ней как к закрытой и потом
 * пытаемся это выяснить»): вид неизвестен — о ключе ничего; открытая — ключ не положен; закрытая без ключа —
 * полоса «ключа нет».
 */
class GroupKindKeyInChatStoreTest {

    private val stream = MutableStateFlow(listOf(ChatLine(dedupKey = "m1", chatId = "g-1", display = MessageDisplay.RECEIVED, text = "x", outgoing = false, atMs = 1, localId = 1)))

    @Test
    fun вид_неизвестен_полосы_нет() = runTest {
        val store = store(backgroundScope)
        runCurrent()
        assertFalse(store.state.value.noGroupKey, "вид не получен — группу закрытой не считаем")
    }

    @Test
    fun открытая_ключ_не_проверяется() = runTest {
        val store = store(backgroundScope)
        store.groupKind(GroupKind.Public)
        runCurrent()
        assertFalse(store.state.value.noGroupKey)
        assertTrue(store.state.value.openGroup)
    }

    @Test
    fun закрытая_без_ключа_полоса() = runTest {
        val store = store(backgroundScope)
        store.groupKind(GroupKind.Personal)
        runCurrent()
        assertTrue(store.state.value.noGroupKey, "закрытая и ключа нет — значит, его реально нет")
    }

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = ChatStore(
        io = kotlinx.coroutines.Dispatchers.Unconfined,
        chatId = "g-1",
        observe = ObserveChat(ChatFeed { _, _ -> stream }),
        send = SendMessage(
            queue = OutgoingQueue { _, _, _, _, _, _ -> true },
            codec = object : MessageBodyCodec {
                override fun encodeText(text: String) = ByteArray(10)
                override fun decodeText(body: ByteArray): String? = null
            },
            keys = DedupKeys { "d-1" },
            maxBodyBytes = 100,
        ),
        scope = scope,
        anyKey = { false },
    )
}
