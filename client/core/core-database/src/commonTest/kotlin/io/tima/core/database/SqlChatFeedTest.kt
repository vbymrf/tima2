package io.tima.core.database

import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxState
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Переписка глазами человека: перевод состояний и порядок.
 *
 * Проверяется на настоящей базе, потому что и то и другое держится на SQL и на
 * столбце `direction` — то есть на данных, а не на коде перевода отдельно.
 */
class SqlChatFeedTest {

    private val db = testDatabase()
    private val outbox = SqlOutboxStore(db)
    private val inbox = SqlInboxStore(db)
    private val feed = SqlChatFeed(db)
    private val chat = ObserveChat(feed)

    private fun исходящее(
        dedupKey: String,
        state: OutboxState,
        clientTs: Long = 1_000,
        serverTs: Long? = null,
    ) {
        outbox.putIfAbsent(
            OutboxEntry(
                dedupKey = dedupKey,
                chatId = "chat-1",
                body = byteArrayOf(1),
                state = OutboxState.QUEUED,
                createdAtMs = clientTs,
            ),
        )
        val запись = outbox.byDedupKey(dedupKey)!!
        outbox.update(
            запись.copy(
                state = state,
                serverMessageId = if (serverTs != null) 42 else null,
            ),
        )
        if (serverTs != null) {
            db.messagesQueries.markSent(OutboxState.SENT.ordinal.toLong(), 42, serverTs, dedupKey)
        }
    }

    private fun входящее(messageId: Long, state: IncomingState, ts: Long = 2_000) {
        inbox.putIfAbsent(
            IncomingEntry(
                chatId = "chat-1",
                messageId = messageId,
                envelope = byteArrayOf(9),
                state = IncomingState.RECEIVED,
                receivedAtMs = ts,
            ),
        )
        val запись = inbox.byKey("chat-1", messageId)!!
        inbox.update(запись.copy(state = state))
    }

    // ── перевод состояний ────────────────────────────────────────────────────

    @Test
    fun ожидание_отправка_и_неудача_различаются_а_очередь_и_конверт_нет() = runTest {
        // Человеку нечего делать с различием «в очереди» и «конверт собран»: это одно
        // ожидание. А «не ушло» требует его решения.
        исходящее("d-1", OutboxState.QUEUED)
        исходящее("d-2", OutboxState.SEALED)
        исходящее("d-3", OutboxState.SENDING)
        исходящее("d-4", OutboxState.DEAD)
        исходящее("d-5", OutboxState.SENT, serverTs = 5_000)

        val строки = chat.page("chat-1").first().associateBy { it.dedupKey }

        assertEquals(MessageDisplay.PENDING, строки["d-1"]?.display)
        assertEquals(MessageDisplay.PENDING, строки["d-2"]?.display)
        assertEquals(MessageDisplay.PENDING, строки["d-3"]?.display)
        assertEquals(MessageDisplay.FAILED, строки["d-4"]?.display)
        assertEquals(MessageDisplay.SENT, строки["d-5"]?.display)
    }

    @Test
    fun совпадающая_нумерация_состояний_не_путает_направления() = runTest {
        // Самое опасное место схемы: состояние 1 означает SEALED у исходящего и
        // UNDECRYPTABLE у входящего. Спутать — значит показать «отправляется» на
        // нечитаемом чужом сообщении.
        исходящее("d-1", OutboxState.SEALED) // state = 1
        входящее(7, IncomingState.UNDECRYPTABLE) // тоже state = 1

        val строки = chat.page("chat-1").first()

        val исход = строки.single { it.outgoing }
        val вход = строки.single { !it.outgoing }
        assertEquals(MessageDisplay.PENDING, исход.display, "исходящее в состоянии 1 — ожидание")
        assertEquals(MessageDisplay.UNREADABLE, вход.display, "входящее в состоянии 1 — нечитаемое")
    }

    @Test
    fun входящее_прочитанное_и_сохранённое_показываются_одинаково() = runTest {
        // Различие «разобрано» и «прочитано» — дело отметок о прочтении, а не списка.
        входящее(1, IncomingState.RECEIVED)
        входящее(2, IncomingState.STORED)
        входящее(3, IncomingState.READ)

        val виды = chat.page("chat-1").first().map { it.display }.toSet()

        assertEquals(setOf(MessageDisplay.RECEIVED), виды)
    }

    @Test
    fun неотправленное_остаётся_видимым() = runTest {
        // Из очереди DEAD уходит, из переписки — нет. Иначе человек не узнает, что
        // сообщение не дошло, и будет ждать ответа на то, чего собеседник не получал.
        исходящее("d-1", OutboxState.DEAD)

        assertEquals(0, outbox.pending().size, "в очереди его уже нет: DEAD терминален")
        assertEquals(1, chat.page("chat-1").first().size, "а в переписке есть")
    }

    // ── порядок ──────────────────────────────────────────────────────────────

    @Test
    fun порядок_смешанного_списка_устойчив() = runTest {
        // Часть сообщений отправлена и получила серверное время, часть лежит в очереди с
        // одним местным. Список не должен переставляться на глазах.
        исходящее("d-старое", OutboxState.SENT, clientTs = 1_000, serverTs = 1_100)
        исходящее("d-в-очереди", OutboxState.QUEUED, clientTs = 3_000)
        исходящее("d-новое", OutboxState.SENT, clientTs = 2_000, serverTs = 5_000)

        val порядок = chat.page("chat-1").first().map { it.dedupKey }

        assertEquals(listOf("d-новое", "d-в-очереди", "d-старое"), порядок, "новое сверху")
    }

    @Test
    fun при_равном_времени_решает_порядок_появления() = runTest {
        // Иначе два сообщения, отправленных в одну миллисекунду, меняются местами при
        // каждом чтении — и список дёргается без причины.
        исходящее("d-1", OutboxState.QUEUED, clientTs = 1_000)
        исходящее("d-2", OutboxState.QUEUED, clientTs = 1_000)

        val дважды = listOf(
            chat.page("chat-1").first().map { it.dedupKey },
            chat.page("chat-1").first().map { it.dedupKey },
        )

        assertEquals(listOf("d-2", "d-1"), дважды[0], "позже добавленное — выше")
        assertEquals(дважды[0], дважды[1], "порядок обязан быть тем же при повторном чтении")
    }

    @Test
    fun время_берётся_серверное_если_оно_есть() = runTest {
        исходящее("d-1", OutboxState.SENT, clientTs = 1_000, serverTs = 7_777)
        исходящее("d-2", OutboxState.QUEUED, clientTs = 2_222)

        val строки = chat.page("chat-1").first().associateBy { it.dedupKey }

        assertEquals(7_777, строки["d-1"]?.atMs, "часы устройства врут — серверные точнее")
        assertEquals(2_222, строки["d-2"]?.atMs, "но пока сообщение не ушло, других нет")
    }

    // ── границы страницы ─────────────────────────────────────────────────────

    @Test
    fun страница_ограничена_и_берёт_последние() = runTest {
        repeat(10) { исходящее("d-$it", OutboxState.QUEUED, clientTs = 1_000L + it) }

        val страница = chat.page("chat-1", limit = 3).first()

        assertEquals(3, страница.size)
        assertEquals(listOf("d-9", "d-8", "d-7"), страница.map { it.dedupKey })
    }

    @Test
    fun бессмысленный_запрос_отвергается() = runTest {
        assertFailsWith<IllegalArgumentException> { chat.page("") }
        assertFailsWith<IllegalArgumentException> { chat.page("chat-1", limit = 0) }
        assertFailsWith<IllegalArgumentException> {
            chat.page("chat-1", limit = ObserveChat.MAX_PAGE + 1)
        }
    }

    @Test
    fun чужая_переписка_в_страницу_не_попадает() = runTest {
        исходящее("d-1", OutboxState.QUEUED)
        outbox.putIfAbsent(
            OutboxEntry(dedupKey = "d-чужое", chatId = "chat-2", body = byteArrayOf(1)),
        )

        val строки = chat.page("chat-1").first()

        assertEquals(1, строки.size)
        assertTrue(строки.all { it.chatId == "chat-1" })
    }
}
