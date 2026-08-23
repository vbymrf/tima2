package io.tima.harness

import io.tima.core.database.SqlChatFeed
import io.tima.core.database.SqlOutboxStore
import io.tima.core.database.TimaDatabase
import io.tima.core.encryption.TextBodyCodec
import io.tima.core.outbox.Outbox
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxPump
import io.tima.core.outbox.UuidDedupKeys
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import io.tima.domain.chat.SendMessage
import io.tima.feature.chat.ChatStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Признак готовности К4, дословно:** «сценарий „отправил — обрыв — повтор —
 * доставлено“ проходит тестом `Store` без сервера».
 *
 * Поэтому сценарий идёт именно через `Store` — то есть так, как его увидит человек:
 * нажатие «отправить», строка в списке, смена её состояния. Настоящее здесь всё, кроме
 * транспорта: правила, база SQLDelight, очередь, насос, кодек тела, поток обновлений.
 */
class StoreScenarioTest {

    private var время = 1_000L
    private var эпоха = 1

    private val db = TimaDatabase(harnessDriver())
    private val outbox = Outbox(SqlOutboxStore(db, харнессШифр()), nowMs = { время })
    private val transport = FakeTransport()
    private val pump = OutboxPump(outbox, maxConcurrent = 3)

    private fun seal(entry: OutboxEntry): ByteArray =
        "эпоха=$эпоха|".encodeToByteArray() + entry.body

    // Эпоха раздаётся по перепискам: ключ escrow у каждой свой, и насос просит его на
    // каждую. Здесь переписка одна.
    private suspend fun прокрутить(): Int =
        pump.runOnce(mapOf("chat-1" to эпоха.toLong()), ::seal, transport::send)

    private fun store(scope: kotlinx.coroutines.CoroutineScope) = ChatStore(
        chatId = "chat-1",
        observe = ObserveChat(SqlChatFeed(db, TextBodyCodec, харнессШифр())),
        send = SendMessage(queue = outbox, codec = TextBodyCodec, keys = UuidDedupKeys),
        scope = scope,
    )

    @Test
    fun отправил_обрыв_повтор_доставлено_глазами_экрана() = runTest {
        val s = store(backgroundScope)
        transport.then(FakeTransport.Behaviour.Offline(retryAfterMs = 1_000))

        // 1. Человек набрал и нажал «отправить».
        s.draftChanged("привет")
        s.sendPressed()

        assertEquals("", s.state.value.draft, "поле очистилось: сообщение уже в списке")
        val ждёт = s.state.first { it.lines.isNotEmpty() }.lines.single()
        assertEquals(MessageDisplay.PENDING, ждёт.display, "и видно как ожидающее")

        // 2. Обрыв. Строка на экране не меняется: человеку нечего с этим делать, очередь
        //    решает сама. Именно поэтому у «ждёт отправки» и «отправляется» один вид.
        assertEquals(1, прокрутить())
        assertEquals(0, transport.deliveredCount())
        assertEquals(
            MessageDisplay.PENDING,
            s.state.first { it.lines.singleOrNull()?.display == MessageDisplay.PENDING }.lines.single().display,
        )

        // 3. Срок пришёл, сеть починилась — и строка сама стала отправленной.
        время += 1_000
        assertEquals(1, прокрутить())

        val ушло = s.state.first { it.lines.singleOrNull()?.display == MessageDisplay.SENT }.lines.single()
        assertEquals(MessageDisplay.SENT, ушло.display)
        assertEquals(ждёт.dedupKey, ушло.dedupKey, "это то же сообщение, а не второе")
        assertEquals(1, transport.deliveredCount(), "и доставлено оно один раз")
    }

    @Test
    fun неотправленное_видно_на_экране_как_неотправленное() = runTest {
        // Из очереди оно уходит, с экрана — нет. Человек обязан узнать, что сообщение не
        // дошло, иначе будет ждать ответа на то, чего собеседник не получал.
        val s = store(backgroundScope)
        transport.then(FakeTransport.Behaviour.Rejected("подпись не сошлась"))

        s.draftChanged("не уйдёт")
        s.sendPressed()
        прокрутить()

        val строка = s.state.first { it.lines.singleOrNull()?.display == MessageDisplay.FAILED }.lines.single()
        assertEquals(MessageDisplay.FAILED, строка.display)
        assertTrue(outbox.pending().isEmpty(), "а в очереди его уже нет")
    }

    @Test
    fun два_сообщения_идут_по_одному_разу_и_новое_сверху() = runTest {
        val s = store(backgroundScope)

        s.draftChanged("первое")
        s.sendPressed()
        время += 1
        s.draftChanged("второе")
        s.sendPressed()

        assertEquals(2, прокрутить())

        val строки = s.state.first { it.lines.size == 2 }.lines
        assertEquals(2, transport.deliveredCount())
        assertEquals(
            2,
            transport.attempts.map { it.dedupKey }.distinct().size,
            "у каждого свой ключ идемпотентности",
        )
        assertTrue(строки[0].localId > строки[1].localId, "новое сверху")
    }
}
