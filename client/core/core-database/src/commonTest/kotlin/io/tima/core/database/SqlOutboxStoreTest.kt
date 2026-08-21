package io.tima.core.database

import io.tima.core.outbox.Outbox
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxState
import io.tima.core.outbox.SendOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Тот же контракт очереди, но на настоящем SQL.
 *
 * **Зачем повторять то, что уже проверено на хранилище в памяти.** Там проверялась
 * машина состояний; здесь — **SQL под ней**: работает ли уникальность `dedup_key`,
 * атомарно ли «выбрал и пометил», возвращает ли `requeueStuck` число строк.
 * Реализация на SQL может нарушить контракт, не тронув ни строки машины.
 */
class SqlOutboxStoreTest {

    private val db = testDatabase()
    private val store = SqlOutboxStore(db)

    private var время = 1_000L
    private val outbox = Outbox(store, nowMs = { время })
    private val тело = byteArrayOf(7, 8, 9)

    private fun поставить(id: String = "d-1") = outbox.enqueue(id, "chat-1", тело)

    @Test
    fun постановка_и_чтение_возвращают_то_же_самое() {
        assertTrue(поставить())
        val e = store.byDedupKey("d-1")
        assertNotNull(e)
        assertEquals("chat-1", e.chatId)
        assertEquals(OutboxState.QUEUED, e.state)
        assertEquals(0, e.attempts)
        // Байты тела обязаны дойти без изменений: это уже сжатый protobuf, и любая
        // «нормализация» BLOB сделала бы сообщение нечитаемым.
        assertTrue(тело.contentEquals(e.body))
    }

    @Test
    fun уникальность_dedup_key_держит_сама_база() {
        // INSERT OR IGNORE плюс changes(): вторая постановка не должна ни падать
        // исключением, ни давать вторую строку.
        assertTrue(поставить("d-1"))
        assertFalse(поставить("d-1"))
        assertEquals(1L, db.messagesQueries.countAll().executeAsOne())
    }

    @Test
    fun повторная_постановка_не_трогает_счётчик_попыток() {
        поставить()
        outbox.sealNext(1) { byteArrayOf(1) }
        outbox.claimForSend()
        outbox.onOutcome("d-1", SendOutcome.Retry())
        val было = store.byDedupKey("d-1")!!

        поставить()

        assertEquals(было.attempts, store.byDedupKey("d-1")!!.attempts)
        assertEquals(было.nextAttemptAtMs, store.byDedupKey("d-1")!!.nextAttemptAtMs)
    }

    @Test
    fun выбор_и_перевод_в_отправку_атомарны() {
        поставить()
        outbox.sealNext(1) { byteArrayOf(1) }
        assertNotNull(store.claimSealed())
        assertNull(store.claimSealed(), "вторая попытка взять то же — ничего")
        assertEquals(OutboxState.SENDING, store.byDedupKey("d-1")?.state)
    }

    @Test
    fun возврат_зависшего_считает_строки() {
        поставить("d-1")
        поставить("d-2")
        outbox.sealNext(1) { byteArrayOf(1) } // d-1 → SEALED
        store.claimSealed() // d-1 → SENDING
        outbox.sealNext(1) { byteArrayOf(1) } // d-2 → SEALED

        assertEquals(2, store.requeueStuck(), "вернуться должны и SENDING, и SEALED")
        assertEquals(OutboxState.QUEUED, store.byDedupKey("d-1")?.state)
        assertEquals(OutboxState.QUEUED, store.byDedupKey("d-2")?.state)
        assertNull(store.byDedupKey("d-2")?.sealedForEpoch, "эпоха обязана сброситься")
    }

    @Test
    fun срок_следующей_попытки_учитывается_в_запросе() {
        поставить()
        outbox.sealNext(1) { byteArrayOf(1) }
        outbox.claimForSend()
        outbox.onOutcome("d-1", SendOutcome.Retry(afterMs = 5_000))

        assertNull(store.nextQueued(время), "срок ещё не пришёл")
        assertNotNull(store.nextQueued(время + 5_000))
    }

    @Test
    fun незавершённые_не_включают_терминальные() {
        поставить("d-1")
        поставить("d-2")
        outbox.sealNext(1) { byteArrayOf(1) }
        outbox.claimForSend()
        outbox.onOutcome("d-1", SendOutcome.Accepted(serverMessageId = 5))

        val незавершённые = store.pending().map { it.dedupKey }
        assertEquals(listOf("d-2"), незавершённые, "SENT в очереди быть не должно")
    }

    @Test
    fun сохранённое_состояние_и_эпоха_читаются_обратно() {
        // Круг «записали — прочитали» для всех полей, которые ведёт очередь: если
        // какое-то не доезжает до базы, машина работает, а перезапуск всё теряет.
        поставить()
        val запечатано = outbox.sealNext(эпоха = 42) { byteArrayOf(1) }
        assertEquals(42, запечатано?.sealedForEpoch)
        assertEquals(42, store.byDedupKey("d-1")?.sealedForEpoch)

        outbox.claimForSend()
        outbox.onOutcome("d-1", SendOutcome.Accepted(serverMessageId = 777))

        val e = store.byDedupKey("d-1")!!
        assertEquals(OutboxState.SENT, e.state)
        assertEquals(777L, e.serverMessageId)
    }

    @Test
    fun неизвестное_состояние_из_базы_не_превращается_в_очередь() {
        // Строка, записанная более новой версией приложения, не должна тихо попасть в
        // очередь и уйти повторно. Лучше падение, чем повторная отправка.
        поставить()
        db.messagesQueries.updateState(
            state = 99, attempts = 0, next_attempt_at = 0,
            sealed_epoch = null, server_id = null, dedup_key = "d-1",
        )
        val ошибка = runCatching { store.byDedupKey("d-1") }.exceptionOrNull()
        assertNotNull(ошибка, "неизвестное состояние обязано быть ошибкой")
        assertTrue(ошибка.message.orEmpty().contains("99"), "в сообщении должно быть значение")
    }

    private fun Outbox.sealNext(эпоха: Int, seal: (OutboxEntry) -> ByteArray) =
        this.sealNext(эпоха, seal)
}
