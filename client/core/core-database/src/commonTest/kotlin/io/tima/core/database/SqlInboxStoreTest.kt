package io.tima.core.database

import io.tima.core.outbox.Inbox
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OpenOutcome
import io.tima.core.outbox.Outbox
import io.tima.core.outbox.OutboxState
import io.tima.core.outbox.SendOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Входящие на настоящем SQL — и, что важнее, **вместе с исходящими в одной таблице**.
 *
 * Главная проверка здесь не про входящие как таковые, а про то, что две машины в одном
 * столбце `state` не путаются. Нумерация у них совпадает: `1` — это `SEALED` у
 * исходящего и `UNDECRYPTABLE` у входящего. Если бы направление не различалось,
 * очередь отправки забирала бы чужие нерасшифрованные сообщения и посылала их обратно
 * на сервер.
 */
class SqlInboxStoreTest {

    private val db = testDatabase()
    private val шифр = тестовыйШифр()
    private val inboxStore = SqlInboxStore(db, шифр)
    private val outboxStore = SqlOutboxStore(db, шифр)

    private var время = 1_000L
    private val inbox = Inbox(inboxStore, nowMs = { время })
    private val outbox = Outbox(outboxStore, nowMs = { время })

    private val конверт = byteArrayOf(5, 6, 7)
    private val тело = byteArrayOf(1)

    @Test
    fun приём_и_чтение_возвращают_то_же_самое() {
        assertTrue(inbox.receive("chat-1", 42, конверт))
        val e = inboxStore.byKey("chat-1", 42)
        assertNotNull(e)
        assertEquals(IncomingState.RECEIVED, e.state)
        assertEquals(42L, e.messageId)
        assertTrue(конверт.contentEquals(e.envelope), "исходник нужен второй попытке разбора")
    }

    @Test
    fun повтор_из_догона_истории_не_даёт_второй_строки() {
        assertTrue(inbox.receive("chat-1", 42, конверт))
        assertFalse(inbox.receive("chat-1", 42, конверт))
        assertEquals(1L, db.messagesQueries.countAll().executeAsOne())
    }

    @Test
    fun одинаковый_номер_в_разных_чатах_это_разные_сообщения() {
        assertTrue(inbox.receive("chat-1", 42, конверт))
        assertTrue(inbox.receive("chat-2", 42, конверт))
        assertEquals(2L, db.messagesQueries.countAll().executeAsOne())
    }

    @Test
    fun причина_нечитаемости_доезжает_до_базы_и_обратно() {
        // Она пишется отдельным столбцом, а не подмешивается в тело: тело шифртекст, и
        // текстовая пометка внутри стала бы байтами, которых никто не расшифрует.
        inbox.receive("chat-1", 42, конверт)
        inbox.openNext({ OpenOutcome.NoKey("обёртки для устройства нет") })

        val e = inboxStore.byKey("chat-1", 42)!!
        assertEquals(IncomingState.UNDECRYPTABLE, e.state)
        assertEquals("обёртки для устройства нет", e.undecryptableReason)
        assertEquals(1, e.attempts)
    }

    @Test
    fun появился_ключ_и_разбор_повторяется() {
        inbox.receive("chat-1", 42, конверт)
        inbox.openNext({ OpenOutcome.NoKey("нет ключа") })

        assertEquals(1, inbox.retryUndecryptable())
        assertEquals(IncomingState.RECEIVED, inboxStore.byKey("chat-1", 42)?.state)

        inbox.openNext({ OpenOutcome.Opened(тело) })
        assertEquals(IncomingState.STORED, inboxStore.byKey("chat-1", 42)?.state)
        // Тело проверяется В БАЗЕ, а не в тестовой переменной: раньше запись содержимого
        // была лямбдой, и все вызывающие передавали пустую — состояние STORED означало
        // «разобрано и потеряно». Теперь строка обязана содержать тело, и закрытое.
        val строка = db.messagesQueries.byDedupKey("chat-1/42").executeAsOne()
        assertFalse(
            тело.contentEquals(строка.body_enc),
            "тело легло в базу открытым — шифрование покоя не сработало",
        )
        assertTrue(
            тело.contentEquals(шифр.open(строка.body_enc)!!),
            "разобранное тело обязано лечь в строку",
        )
    }

    // ── главное: две машины в одном столбце ─────────────────────────────────

    @Test
    fun очередь_отправки_не_видит_входящих() {
        // Входящее в состоянии UNDECRYPTABLE имеет state = 1 — то же число, что SEALED
        // у исходящего. Без direction очередь забрала бы его на отправку.
        inbox.receive("chat-1", 42, конверт)
        inbox.openNext({ OpenOutcome.NoKey("нет ключа") })
        assertEquals(1L, IncomingState.UNDECRYPTABLE.ordinal.toLong(), "предпосылка теста")
        assertEquals(1L, OutboxState.SEALED.ordinal.toLong(), "предпосылка теста")

        assertNull(outboxStore.claimSealed(), "чужое нерасшифрованное — не наш конверт")
        assertNull(outboxStore.nextQueued(время + 1_000_000))
        assertEquals(0, outbox.pending().size)
    }

    @Test
    fun приём_входящих_не_видит_исходящих() {
        // И обратная сторона: исходящее в QUEUED имеет state = 0 — то же число, что
        // RECEIVED у входящего.
        outbox.enqueue("d-1", "chat-1", тело)
        assertEquals(0L, OutboxState.QUEUED.ordinal.toLong(), "предпосылка теста")
        assertEquals(0L, IncomingState.RECEIVED.ordinal.toLong(), "предпосылка теста")

        assertNull(inboxStore.nextReceived(), "своё исходящее нельзя «разбирать»")
        assertEquals(0, inbox.pending().size)
    }

    @Test
    fun две_машины_работают_в_одной_таблице_одновременно() {
        // Обычная жизнь чата: своё уходит, чужое приходит. Обе очереди обязаны видеть
        // ровно своё.
        outbox.enqueue("d-1", "chat-1", тело)
        inbox.receive("chat-1", 42, конверт)

        assertEquals(2L, db.messagesQueries.countAll().executeAsOne())

        outbox.sealNext("chat-1", 1) { byteArrayOf(1) }
        outbox.claimForSend()
        outbox.onOutcome("d-1", SendOutcome.Accepted(serverMessageId = 100))
        inbox.openNext({ OpenOutcome.Opened(тело) })

        assertEquals(OutboxState.SENT, outboxStore.byDedupKey("d-1")?.state)
        assertEquals(IncomingState.STORED, inboxStore.byKey("chat-1", 42)?.state)
        assertEquals(0, outbox.pending().size)
        assertEquals(0, inbox.pending().size)
    }

    @Test
    fun переписка_читается_одним_списком_обоих_направлений() {
        // Ради этого они и лежат в одной таблице: чат — один список с одной
        // сортировкой, а не склейка двух наборов при каждом открытии.
        outbox.enqueue("d-1", "chat-1", тело)
        inbox.receive("chat-1", 42, конверт)

        val список = db.messagesQueries.chatPage("chat-1", 10).executeAsList()
        assertEquals(2, список.size)
        assertEquals(setOf(0L, 1L), список.map { it.direction }.toSet())
    }
}
