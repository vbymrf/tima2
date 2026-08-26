package io.tima.core.outbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Насос: предел одновременных отправок и то, что одно упавшее сообщение не
 * останавливает остальные.
 *
 * Предел — требование плана: «иначе очередь после суток офлайна выстреливает залпом».
 * Проверяется не по времени, а по **пиковому числу одновременных отправок**: тест по
 * секундомеру был бы то зелёным, то красным без изменения кода.
 */
class OutboxPumpTest {

    private var time = 1_000L
    private val store = InMemoryOutboxStore()
    private val outbox = Outbox(store, nowMs = { time })
    private val body = byteArrayOf(1)

    private val seal: (OutboxEntry) -> ByteArray = { byteArrayOf(0x7F) + it.body }

    private fun put(n: Int) = repeat(n) { outbox.enqueue("d-$it", CHAT, body) }

    /** Проход по единственной переписке: настоящая подпись просит эпоху на каждую. */
    private suspend fun OutboxPump.runOnce(
        epoch: Int,
        seal: (OutboxEntry) -> ByteArray,
        send: suspend (ReadyToSend) -> SendOutcome,
    ) = runOnce(mapOf(CHAT to epoch.toLong()), seal, send)

    @Test
    fun одновременных_отправок_не_больше_предела() = runTest {
        put(10)

        var now = 0
        var peak = 0
        val hold = CompletableDeferred<Unit>()

        val pump = OutboxPump(outbox, maxConcurrent = 3)
        // async от области самого теста, а не backgroundScope: фоновые корутины
        // планировщик не считает работой, и advanceUntilIdle объявил бы простой, ни разу
        // не дав проходу начаться (пик остался бы нулём — так и было).
        val pass = async {
            pump.runOnce(epoch = 1, seal = seal) {
                now++
                peak = maxOf(peak, now)
                // Все отправки висят, пока не отпустим: так пик виден целиком, а не
                // зависит от того, кто успел завершиться.
                hold.await()
                now--
                SendOutcome.Accepted(1)
            }
        }

        // Даём корутинам дойти до ожидания.
        testScheduler.advanceUntilIdle()
        assertEquals(3, peak, "одновременно должно висеть ровно три отправки")

        hold.complete(Unit)
        val handled = pass.await()

        assertEquals(10, handled)
        assertEquals(3, peak, "предел не должен превышаться и после разблокировки")
        assertEquals(10, store.countBy(OutboxState.SENT))
    }

    @Test
    fun упавшее_сообщение_не_останавливает_остальные() = runTest {
        // Одно неотправляемое сообщение не должно задерживать переписку — это то же
        // требование, что и «отложенная запись не загораживает готовую», но на уровне
        // прохода.
        put(5)

        val handled = OutboxPump(outbox, maxConcurrent = 2).runOnce(1, seal) { ready ->
            if (ready.entry.dedupKey == "d-2") {
                SendOutcome.Permanent("подпись не сошлась")
            } else {
                SendOutcome.Accepted(1)
            }
        }

        assertEquals(5, handled)
        assertEquals(4, store.countBy(OutboxState.SENT))
        assertEquals(1, store.countBy(OutboxState.DEAD))
    }

    @Test
    fun временные_отказы_возвращаются_в_очередь_целиком() = runTest {
        put(4)

        OutboxPump(outbox, maxConcurrent = 4).runOnce(1, seal) { SendOutcome.Retry() }

        assertEquals(4, store.countBy(OutboxState.QUEUED), "всё вернулось, ничего не потеряно")
        // Конверты остаются в памяти: эпоха не сменилась, значит повтор уйдёт теми же
        // байтами без нового запечатывания. Смену эпохи проверяет тест ниже.
        assertEquals(4, outbox.cachedEnvelopeCount())
    }

    @Test
    fun пустая_очередь_даёт_ноль_и_не_зовёт_транспорт() = runTest {
        var calls = 0
        val handled = OutboxPump(outbox).runOnce(1, seal) {
            calls++
            SendOutcome.Accepted(1)
        }
        assertEquals(0, handled)
        assertEquals(0, calls, "по пустой очереди в сеть ходить незачем")
    }

    @Test
    fun проход_конечен_и_не_забирает_больше_предела_партии() = runTest {
        // Проход держит запечатанные конверты в памяти, поэтому берёт ограниченную
        // партию. Очередь после долгого офлайна уйдёт за несколько проходов — это
        // правильнее, чем один проход на всю память устройства.
        put(OutboxPump.BATCH_LIMIT + 10)

        val first = OutboxPump(outbox, maxConcurrent = 5).runOnce(1, seal) {
            SendOutcome.Accepted(1)
        }

        assertEquals(OutboxPump.BATCH_LIMIT, first)
        assertEquals(10, store.countBy(OutboxState.QUEUED), "остаток ждёт следующего прохода")
    }

    @Test
    fun смена_эпохи_между_проходами_перезапечатывает() = runTest {
        // Ради этого запечатывание и позднее: за время ожидания в очереди ключ эпохи
        // escrow успевает смениться.
        put(2)
        var sealed = 0
        val counting: (OutboxEntry) -> ByteArray = { sealed++; byteArrayOf(1) }

        OutboxPump(outbox, maxConcurrent = 2).runOnce(1, counting) { SendOutcome.Retry() }
        assertEquals(2, sealed)

        time += 1_000
        outbox.onEpochChanged(CHAT, 2L)
        OutboxPump(outbox, maxConcurrent = 2).runOnce(epoch = 2, seal = counting) {
            SendOutcome.Accepted(1)
        }

        assertEquals(4, sealed, "под новую эпоху конверты собираются заново")
        assertEquals(2, store.countBy(OutboxState.SENT))
    }

    @Test
    fun предел_меньше_единицы_отвергается_при_создании() {
        // Ноль одновременных отправок — это остановка очереди, оформленная как
        // настройка. Такое лучше не давать выразить.
        assertFailsWith<IllegalArgumentException> { OutboxPump(outbox, maxConcurrent = 0) }
    }

    @Test
    fun исключение_из_транспорта_не_проглатывается_молча() = runTest {
        // Транспорт обязан отдавать беду исходом. Если он всё же бросил, проход должен
        // упасть, а не сделать вид, что сообщение отправлено.
        put(1)
        assertFailsWith<IllegalStateException> {
            OutboxPump(outbox).runOnce(1, seal) { error("транспорт бросил") }
        }
        // И запись осталась в SENDING — её вернёт recoverOnStart, а не молчание.
        assertEquals(1, store.countBy(OutboxState.SENDING))
        assertEquals(1, outbox.recoverOnStart())
    }

    private companion object {
        const val CHAT = "chat-1"
    }
}
