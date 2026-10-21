package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.HealStep
import io.tima.domain.chat.KeyHealing
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Полоса «ключа этой группы на устройстве нет» (заказчик 2026-10-21: «при каждом заходе в группу…
 * висит не более 1 секунды»): пока лечение не ответило, её нет; ответило «не вышло» — есть; открытая
 * группа — нет вовсе.
 */
class NoKeyBannerInChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())

    @Test
    fun пока_лечение_не_ответило_полосы_нет() = runTest {
        val answer = CompletableDeferred<HealStep>()
        val store = store(backgroundScope, { answer.await() })
        runCurrent()
        assertFalse(store.state.value.noGroupKey, "лечение идёт — полосы нет")
        answer.complete(HealStep.NotEncrypted)
        runCurrent()
        assertFalse(store.state.value.noGroupKey, "открытая группа — полосы нет")
        assertTrue(store.state.value.openGroup)
    }

    @Test
    fun лечение_не_вышло_полоса_есть() = runTest {
        val answer = CompletableDeferred<HealStep>()
        val store = store(backgroundScope, { answer.await() })
        runCurrent()
        answer.complete(HealStep.NeedAsk)
        runCurrent()
        assertTrue(store.state.value.noGroupKey, "ключ у участников — просить: полоса с «Запросить ключ»")
    }

    @Test
    fun открытая_группа_известна_заранее() = runTest {
        var asked = 0
        val store = store(backgroundScope, { asked++; HealStep.NotEncrypted }, openAtStart = true)
        runCurrent()
        assertFalse(store.state.value.noGroupKey)
        assertEquals(0, asked, "ключ открытой группе не положен — лечить незачем")
    }

    @Test
    fun открытая_по_вершине_полосы_нет_и_после_отказа() = runTest {
        // Выпуск ключа открытой группе сервер отвергает (400), лечение кончается «не вышло».
        val store = store(backgroundScope, { HealStep.Unknown })
        runCurrent()
        assertTrue(store.state.value.noGroupKey)
        store.openKnown()
        assertFalse(store.state.value.noGroupKey, "вершина в ленте состояний — группа открытая")
        stream.value = listOf(ChatLine(dedupKey = "m1", chatId = "g-1", display = io.tima.domain.chat.MessageDisplay.RECEIVED, text = "x", outgoing = false, atMs = 1, localId = 1))
        runCurrent()
        assertFalse(store.state.value.noGroupKey, "и пересчёт по ленте её не возвращает")
    }

    private fun store(scope: kotlinx.coroutines.CoroutineScope, heal: KeyHealing, openAtStart: Boolean = false) = ChatStore(
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
        heal = heal,
        openAtStart = openAtStart,
    )
}
