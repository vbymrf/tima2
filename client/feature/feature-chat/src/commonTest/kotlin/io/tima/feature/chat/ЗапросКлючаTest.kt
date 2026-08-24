package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.RecoveryStep
import io.tima.domain.chat.RequestGroupKeys
import io.tima.domain.chat.SendMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Запрос недостающего ключа глазами экрана переписки.
 *
 * Проверяется различие исходов, а не факт вызова: «просьба ушла», «просить некого» и
 * «нужна фраза» требуют от человека разного, и слепить их в «ошибку» — значит заставить
 * его жать кнопку снова.
 */
class ЗапросКлючаTest {

    @Test
    fun у_личной_переписки_кнопки_нет() = runTest {
        // Просить нечего и не у кого: обёртки личного сообщения адресованы устройствам,
        // а не версиям ключа.
        val store = store(backgroundScope, запрос = null)
        assertFalse(store.state.value.можноПроситьКлюч)

        store.запроситьКлюч()
        runCurrent()
        assertEquals(null, store.state.value.notice, "личная переписка что-то запросила")
    }

    @Test
    fun просьба_ушла_и_названо_скольким() = runTest {
        val store = store(backgroundScope, запрос = RequestGroupKeys { RecoveryStep.Requested(2, 3) })
        assertTrue(store.state.value.можноПроситьКлюч)

        store.запроситьКлюч()
        runCurrent()

        val уведомление = assertIs<ChatNotice.KeysAsked>(store.state.value.notice)
        assertEquals(3, уведомление.устройствам)
        assertFalse(store.state.value.ждёмКлюч)
    }

    @Test
    fun просить_некого_говорится_отдельно() = runTest {
        // Ждать бесполезно: нужных версий нет ни у кого. «Повторите позже» тут было бы
        // прямой неправдой.
        val store = store(backgroundScope, запрос = RequestGroupKeys { RecoveryStep.Requested(2, 0) })
        store.запроситьКлюч()
        runCurrent()
        assertIs<ChatNotice.KeysNoHelpers>(store.state.value.notice)
    }

    @Test
    fun нужна_фраза_это_не_ошибка() = runTest {
        val store = store(backgroundScope, запрос = RequestGroupKeys { RecoveryStep.NeedsSecretPhrase })
        store.запроситьКлюч()
        runCurrent()
        assertIs<ChatNotice.KeysNeedPhrase>(store.state.value.notice)
    }

    @Test
    fun второе_нажатие_не_будит_чужие_устройства_дважды() = runTest {
        // Просьба уходит живым устройствам участников. Дублировать её из-за нетерпения —
        // значит будить чужие телефоны второй раз без всякой пользы.
        var просьб = 0
        val store = store(backgroundScope, запрос = RequestGroupKeys { просьб++; RecoveryStep.Requested(1, 1) })
        store.запроситьКлюч()
        store.запроситьКлюч()
        runCurrent()
        assertEquals(1, просьб)
    }

    private val поток = MutableStateFlow<List<ChatLine>>(emptyList())

    private fun store(scope: kotlinx.coroutines.CoroutineScope, запрос: RequestGroupKeys?) = ChatStore(
        chatId = "gggggggg-0000-0000-0000-000000000001",
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
        requestKeys = запрос,
    )
}
