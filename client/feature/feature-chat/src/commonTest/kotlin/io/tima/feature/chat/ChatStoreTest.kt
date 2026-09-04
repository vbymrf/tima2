package io.tima.feature.chat

import io.tima.domain.chat.ChatFeed
import io.tima.domain.chat.ChatLine
import io.tima.domain.chat.DedupKeys
import io.tima.domain.chat.MarkRead
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.ReadMarks
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.OutgoingQueue
import io.tima.domain.chat.SendMessage
import io.tima.domain.chat.SendMessageResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Правила окна переписки. Главное из них — набранное человеком не теряется.
 */
class ChatStoreTest {

    private val stream = MutableStateFlow<List<ChatLine>>(emptyList())
    private val feed = ChatFeed { _, _ -> stream }

    private val queue = mutableListOf<String>()
    private var taken = emptySet<String>()
    private var bodySize = 10

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = ChatStore(
        chatId = "chat-1",
        observe = ObserveChat(feed),
        send = SendMessage(
            queue = OutgoingQueue { key, _, _, _ ->
                queue += key
                key !in taken
            },
            codec = object : MessageBodyCodec {
                override fun encodeText(text: String) = ByteArray(bodySize)
                override fun decodeText(body: ByteArray): String? = null
            },
            keys = DedupKeys { "d-${queue.size + 1}" },
            maxBodyBytes = 100,
        ),
        scope = scope,
    )

    private fun line(dedupKey: String, display: MessageDisplay) = ChatLine(
        dedupKey = dedupKey,
        chatId = "chat-1",
        display = display,
        outgoing = true,
        atMs = 1_000,
        localId = 1,
    )

    /**
     * **Открытая переписка отмечается прочитанной — и на каждом обновлении.**
     *
     * Не только при открытии: пока переписка на экране, приходящее человек видит, и
     * держать при этом янтарную точку значит врать ему. Проверяется числом вызовов, а не
     * фактом одного: «отметили один раз при открытии» — это другое поведение.
     */
    @Test
    fun открытая_переписка_отмечается_прочитанной() = runTest {
        val marked = mutableListOf<String>()
        val store = ChatStore(
            chatId = "chat-1",
            observe = ObserveChat(feed),
            send = SendMessage(
                queue = OutgoingQueue { _, _, _, _ -> true },
                codec = object : MessageBodyCodec {
                    override fun encodeText(text: String) = ByteArray(1)
                    override fun decodeText(body: ByteArray): String? = null
                },
                keys = DedupKeys { "d-1" },
            ),
            scope = backgroundScope,
            markRead = MarkRead(ReadMarks { chatId -> marked += chatId; 1 }),
        )

        stream.value = listOf(line("d-1", MessageDisplay.RECEIVED))
        store.state.first { it.lines.isNotEmpty() }

        stream.value = listOf(line("d-1", MessageDisplay.RECEIVED), line("d-2", MessageDisplay.RECEIVED))
        store.state.first { it.lines.size == 2 }

        assertTrue(marked.size >= 2, "отмечать надо и при открытии, и на каждом обновлении: $marked")
        assertTrue(marked.all { it == "chat-1" }, "чужую переписку отмечать нечем: $marked")
    }

    @Test
    fun удачная_отправка_чистит_поле() = runTest {
        // Сообщение уже видно в списке, и оставленный текст выглядел бы как второе.
        val s = store(backgroundScope)
        s.draftChanged("привет")

        val outcome = s.sendPressed()

        assertIs<SendMessageResult.Queued>(outcome)
        assertEquals("", s.state.value.draft)
        assertNull(s.state.value.notice)
    }

    @Test
    fun отказ_по_размеру_поле_НЕ_чистит() = runTest {
        // Главное правило. Текст написан человеком, а не нами, и восстановить его нам
        // нечем. В v1 поле очищалось до подтверждения, и отвергнутое сообщение исчезало
        // вместе с набранным.
        bodySize = 101
        val s = store(backgroundScope)
        s.draftChanged("очень длинное сообщение")

        val outcome = s.sendPressed()

        assertIs<SendMessageResult.TooLarge>(outcome)
        assertEquals("очень длинное сообщение", s.state.value.draft, "набранное обязано остаться")
        val complaint = s.state.value.notice
        assertIs<ChatNotice.TooLarge>(complaint)
        assertEquals(101, complaint.bytes)
        assertEquals(100, complaint.limit, "предел в сообщении нужен: «слишком большое» без числа бесполезно")
    }

    @Test
    fun нажатие_по_пустому_полю_ничего_не_делает() = runTest {
        val s = store(backgroundScope)

        val outcome = s.sendPressed()

        assertEquals(SendMessageResult.Empty, outcome)
        assertTrue(queue.isEmpty(), "в очередь ничего не легло")
        assertNull(s.state.value.notice, "и жаловаться незачем: человек сам видит пустое поле")
    }

    @Test
    fun уже_стоящее_в_очереди_тоже_чистит_поле() = runTest {
        // Повторное нажатие: сообщение в очереди, значит поле держать не надо.
        taken = setOf("d-1")
        val s = store(backgroundScope)
        s.draftChanged("привет")

        assertIs<SendMessageResult.AlreadyQueued>(s.sendPressed())
        assertEquals("", s.state.value.draft)
    }

    @Test
    fun список_обновляется_сам() = runTest {
        // Поток, а не нажатие: пришедшее сообщение и смена состояния отправки появляются
        // на экране без участия экрана.
        val s = store(backgroundScope)
        assertTrue(s.state.value.lines.isEmpty())

        stream.value = listOf(line("d-1", MessageDisplay.PENDING))
        // Ждём состояние, а не тикаем планировщиком: сборщик живёт в фоновой области, а
        // её планировщик работой не считает — advanceUntilIdle объявит простой, ни разу
        // не дав сборщику начаться. Тот же капкан, что с проверкой пика в насосе.
        assertEquals(
            MessageDisplay.PENDING,
            s.state.first { it.lines.isNotEmpty() }.lines.single().display,
        )

        // Очередь отправила — состояние строки меняется тем же потоком.
        stream.value = listOf(line("d-1", MessageDisplay.SENT))
        assertEquals(
            MessageDisplay.SENT,
            s.state.first { it.lines.singleOrNull()?.display == MessageDisplay.SENT }
                .lines.single().display,
        )
    }

    @Test
    fun правка_текста_убирает_прежнюю_жалобу() = runTest {
        // Иначе сообщение о слишком большом висит над уже исправленным текстом.
        bodySize = 101
        val s = store(backgroundScope)
        s.draftChanged("длинное")
        s.sendPressed()
        assertIs<ChatNotice.TooLarge>(s.state.value.notice)

        s.draftChanged("короче")

        assertNull(s.state.value.notice)
    }

    @Test
    fun жалобу_можно_закрыть() = runTest {
        bodySize = 101
        val s = store(backgroundScope)
        s.draftChanged("длинное")
        s.sendPressed()

        s.noticeDismissed()

        assertNull(s.state.value.notice)
        assertEquals("длинное", s.state.value.draft, "закрытие жалобы текста не забирает")
    }
}
