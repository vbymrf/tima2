package io.tima.core.database

import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxState
import io.tima.domain.chat.MessageBodyCodec
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
    private val cipher = testCipher()

    /** Байты, которые кодек телом не признает: так выглядит конверт до разбора. */
    private val bodyWithoutEnvelope = Codec.UNREADABLE
    private val outbox = SqlOutboxStore(db, cipher)
    private val inbox = SqlInboxStore(db, cipher)
    private val feed = SqlChatFeed(db, Codec, cipher, Me)
    private val chat = ObserveChat(feed)

    private fun outgoing(
        dedupKey: String,
        state: OutboxState,
        clientTs: Long = 1_000,
        serverTs: Long? = null,
        body: ByteArray = Codec.encodeText("привет"),
    ) {
        outbox.putIfAbsent(
            OutboxEntry(
                dedupKey = dedupKey,
                chatId = "chat-1",
                body = body,
                state = OutboxState.QUEUED,
                createdAtMs = clientTs,
            ),
        )
        val entry = outbox.byDedupKey(dedupKey)!!
        outbox.update(
            entry.copy(
                state = state,
                serverMessageId = if (serverTs != null) 42 else null,
            ),
        )
        if (serverTs != null) {
            db.messagesQueries.markSent(OutboxState.SENT.ordinal.toLong(), 42, serverTs, dedupKey)
        }
    }

    private fun incoming(
        messageId: Long,
        state: IncomingState,
        ts: Long = 2_000,
        body: ByteArray = Codec.encodeText("ответ"),
    ) {
        inbox.putIfAbsent(
            IncomingEntry(
                chatId = "chat-1",
                messageId = messageId,
                envelope = body,
                state = IncomingState.RECEIVED,
                receivedAtMs = ts,
            ),
        )
        val entry = inbox.byKey("chat-1", messageId)!!
        inbox.update(entry.copy(state = state))
    }

    // ── перевод состояний ────────────────────────────────────────────────────

    @Test
    fun ожидание_отправка_и_неудача_различаются_а_очередь_и_конверт_нет() = runTest {
        // Человеку нечего делать с различием «в очереди» и «конверт собран»: это одно
        // ожидание. А «не ушло» требует его решения.
        outgoing("d-1", OutboxState.QUEUED)
        outgoing("d-2", OutboxState.SEALED)
        outgoing("d-3", OutboxState.SENDING)
        outgoing("d-4", OutboxState.DEAD)
        outgoing("d-5", OutboxState.SENT, serverTs = 5_000)

        val lines = chat.page("chat-1").first().associateBy { it.dedupKey }

        assertEquals(MessageDisplay.PENDING, lines["d-1"]?.display)
        assertEquals(MessageDisplay.PENDING, lines["d-2"]?.display)
        assertEquals(MessageDisplay.PENDING, lines["d-3"]?.display)
        assertEquals(MessageDisplay.FAILED, lines["d-4"]?.display)
        assertEquals(MessageDisplay.SENT, lines["d-5"]?.display)
    }

    @Test
    fun совпадающая_нумерация_состояний_не_путает_направления() = runTest {
        // Самое опасное место схемы: состояние 1 означает SEALED у исходящего и
        // UNDECRYPTABLE у входящего. Спутать — значит показать «отправляется» на
        // нечитаемом чужом сообщении.
        outgoing("d-1", OutboxState.SEALED) // state = 1
        incoming(7, IncomingState.UNDECRYPTABLE) // тоже state = 1

        val lines = chat.page("chat-1").first()

        val outcome = lines.single { it.outgoing }
        val entry = lines.single { !it.outgoing }
        assertEquals(MessageDisplay.PENDING, outcome.display, "исходящее в состоянии 1 — ожидание")
        assertEquals(MessageDisplay.UNREADABLE, entry.display, "входящее в состоянии 1 — нечитаемое")
    }

    @Test
    fun входящее_прочитанное_и_сохранённое_показываются_одинаково() = runTest {
        // Различие «разобрано» и «прочитано» — дело отметок о прочтении, а не списка.
        incoming(1, IncomingState.RECEIVED)
        incoming(2, IncomingState.STORED)
        incoming(3, IncomingState.READ)

        val kinds = chat.page("chat-1").first().map { it.display }.toSet()

        assertEquals(setOf(MessageDisplay.RECEIVED), kinds)
    }

    @Test
    fun неотправленное_остаётся_видимым() = runTest {
        // Из очереди DEAD уходит, из переписки — нет. Иначе человек не узнает, что
        // сообщение не дошло, и будет ждать ответа на то, чего собеседник не получал.
        outgoing("d-1", OutboxState.DEAD)

        assertEquals(0, outbox.pending().size, "в очереди его уже нет: DEAD терминален")
        assertEquals(1, chat.page("chat-1").first().size, "а в переписке есть")
    }

    // ── текст ────────────────────────────────────────────────────────────────

    @Test
    fun текст_доезжает_до_строки() = runTest {
        // Экран переписки без текста не бывает, а тело лежит в базе упакованным. Читает
        // его переходник — тем же кодеком, которым тело уходит на провод.
        outgoing("d-1", OutboxState.SENT, serverTs = 5_000, body = Codec.encodeText("привет"))
        incoming(7, IncomingState.STORED, body = Codec.encodeText("и тебе"))

        val lines = chat.page("chat-1").first()

        assertEquals("привет", lines.single { it.outgoing }.text)
        assertEquals("и тебе", lines.single { !it.outgoing }.text)
    }

    @Test
    fun нечитаемое_тело_не_роняет_страницу() = runTest {
        // Одна испорченная запись не должна лишать человека всей переписки: страница
        // приходит целиком, а у нечитаемой строки текста просто нет.
        incoming(1, IncomingState.UNDECRYPTABLE, body = Codec.UNREADABLE)
        outgoing("d-1", OutboxState.SENT, serverTs = 5_000)

        val lines = chat.page("chat-1").first()

        assertEquals(2, lines.size, "нечитаемое остаётся строкой: человек видит, что сообщение было")
        assertEquals(null, lines.single { !it.outgoing }.text)
        assertEquals(MessageDisplay.UNREADABLE, lines.single { !it.outgoing }.display)
        assertEquals("привет", lines.single { it.outgoing }.text, "соседнее читается как обычно")
    }

    /**
     * У входящего текст появляется **только после разбора**.
     *
     * Пока разбор не удался, в столбце лежит конверт, а не тело: по нему идёт повтор,
     * когда появится ключ. Разбирать конверт кодеком тела незачем — это не тело, и до
     * этой правки строка молча выходила нечитаемой навсегда, потому что тело не
     * записывалось вообще нигде.
     */
    @Test
    fun текст_входящего_появляется_только_после_разбора() = runTest {
        val machine = io.tima.core.outbox.Inbox(inbox, nowMs = { 2_000 })
        machine.receive("chat-1", 42, bodyWithoutEnvelope)

        assertEquals(null, chat.page("chat-1").first().single().text, "конверт — это ещё не текст")

        machine.openNext { io.tima.core.outbox.OpenOutcome.Opened(Codec.encodeText("и тебе"), PEER) }

        val line = chat.page("chat-1").first().single()
        assertEquals("и тебе", line.text, "после разбора тело обязано быть в строке")
        assertEquals(MessageDisplay.RECEIVED, line.display)
    }

    /**
     * **Сообщение со своего второго устройства — своё.**
     *
     * Оно приходит входящим: его принёс живой канал. Но написал его человек сам, с
     * телефона, и на ПК он хочет видеть его справа и без имени автора. Отличить можно
     * только по проверенному отправителю — потому он и записывается.
     */
    @Test
    fun входящее_от_себя_же_считается_своим() = runTest {
        val machine = io.tima.core.outbox.Inbox(inbox, nowMs = { 2_000 })
        machine.receive("chat-1", 42, bodyWithoutEnvelope)
        machine.openNext { io.tima.core.outbox.OpenOutcome.Opened(Codec.encodeText("со телефона"), Me) }

        val line = chat.page("chat-1").first().single()

        assertTrue(line.outgoing, "написанное мною с другого устройства — моё")
        assertEquals("со телефона", line.text)
    }

    @Test
    fun входящее_от_собеседника_остаётся_чужим() = runTest {
        val machine = io.tima.core.outbox.Inbox(inbox, nowMs = { 2_000 })
        machine.receive("chat-1", 42, bodyWithoutEnvelope)
        machine.openNext { io.tima.core.outbox.OpenOutcome.Opened(Codec.encodeText("привет"), PEER) }

        assertTrue(!chat.page("chat-1").first().single().outgoing)
    }

    /**
     * Неразобранное входящее своим не считается.
     *
     * Автора у него ещё нет — `sender_id` пуст, и это единственное честное значение: до
     * сошедшейся подписи мы не знаем, кто написал.
     */
    @Test
    fun неразобранное_входящее_не_своё() = runTest {
        io.tima.core.outbox.Inbox(inbox, nowMs = { 2_000 }).receive("chat-1", 42, bodyWithoutEnvelope)

        assertTrue(!chat.page("chat-1").first().single().outgoing)
    }

    // ── порядок ──────────────────────────────────────────────────────────────

    @Test
    fun порядок_смешанного_списка_устойчив() = runTest {
        // Часть сообщений отправлена и получила серверное время, часть лежит в очереди с
        // одним местным. Список не должен переставляться на глазах.
        outgoing("d-старое", OutboxState.SENT, clientTs = 1_000, serverTs = 1_100)
        outgoing("d-в-очереди", OutboxState.QUEUED, clientTs = 3_000)
        outgoing("d-новое", OutboxState.SENT, clientTs = 2_000, serverTs = 5_000)

        val order = chat.page("chat-1").first().map { it.dedupKey }

        assertEquals(listOf("d-новое", "d-в-очереди", "d-старое"), order, "новое сверху")
    }

    @Test
    fun при_равном_времени_решает_порядок_появления() = runTest {
        // Иначе два сообщения, отправленных в одну миллисекунду, меняются местами при
        // каждом чтении — и список дёргается без причины.
        outgoing("d-1", OutboxState.QUEUED, clientTs = 1_000)
        outgoing("d-2", OutboxState.QUEUED, clientTs = 1_000)

        val twice = listOf(
            chat.page("chat-1").first().map { it.dedupKey },
            chat.page("chat-1").first().map { it.dedupKey },
        )

        assertEquals(listOf("d-2", "d-1"), twice[0], "позже добавленное — выше")
        assertEquals(twice[0], twice[1], "порядок обязан быть тем же при повторном чтении")
    }

    @Test
    fun время_берётся_серверное_если_оно_есть() = runTest {
        outgoing("d-1", OutboxState.SENT, clientTs = 1_000, serverTs = 7_777)
        outgoing("d-2", OutboxState.QUEUED, clientTs = 2_222)

        val lines = chat.page("chat-1").first().associateBy { it.dedupKey }

        assertEquals(7_777, lines["d-1"]?.atMs, "часы устройства врут — серверные точнее")
        assertEquals(2_222, lines["d-2"]?.atMs, "но пока сообщение не ушло, других нет")
    }

    // ── границы страницы ─────────────────────────────────────────────────────

    @Test
    fun страница_ограничена_и_берёт_последние() = runTest {
        repeat(10) { outgoing("d-$it", OutboxState.QUEUED, clientTs = 1_000L + it) }

        val page = chat.page("chat-1", limit = 3).first()

        assertEquals(3, page.size)
        assertEquals(listOf("d-9", "d-8", "d-7"), page.map { it.dedupKey })
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
        outgoing("d-1", OutboxState.QUEUED)
        outbox.putIfAbsent(
            OutboxEntry(dedupKey = "d-чужое", chatId = "chat-2", body = byteArrayOf(1)),
        )

        val lines = chat.page("chat-1").first()

        assertEquals(1, lines.size)
        assertTrue(lines.all { it.chatId == "chat-1" })
    }

    private companion object {
        /** Кто я и кто собеседник: своё сообщение отличается от чужого отправителем. */
        const val Me = "u-я"
        const val PEER = "u-аня"
    }
}
