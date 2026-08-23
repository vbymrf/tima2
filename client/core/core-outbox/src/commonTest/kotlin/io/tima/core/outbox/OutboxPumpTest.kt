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

    private var время = 1_000L
    private val store = InMemoryOutboxStore()
    private val outbox = Outbox(store, nowMs = { время })
    private val тело = byteArrayOf(1)

    private val запечатать: (OutboxEntry) -> ByteArray = { byteArrayOf(0x7F) + it.body }

    private fun поставить(n: Int) = repeat(n) { outbox.enqueue("d-$it", ЧАТ, тело) }

    /** Проход по единственной переписке: настоящая подпись просит эпоху на каждую. */
    private suspend fun OutboxPump.runOnce(
        эпоха: Int,
        seal: (OutboxEntry) -> ByteArray,
        send: suspend (ReadyToSend) -> SendOutcome,
    ) = runOnce(mapOf(ЧАТ to эпоха.toLong()), seal, send)

    @Test
    fun одновременных_отправок_не_больше_предела() = runTest {
        поставить(10)

        var сейчас = 0
        var пик = 0
        val держим = CompletableDeferred<Unit>()

        val насос = OutboxPump(outbox, maxConcurrent = 3)
        // async от области самого теста, а не backgroundScope: фоновые корутины
        // планировщик не считает работой, и advanceUntilIdle объявил бы простой, ни разу
        // не дав проходу начаться (пик остался бы нулём — так и было).
        val проход = async {
            насос.runOnce(эпоха = 1, seal = запечатать) {
                сейчас++
                пик = maxOf(пик, сейчас)
                // Все отправки висят, пока не отпустим: так пик виден целиком, а не
                // зависит от того, кто успел завершиться.
                держим.await()
                сейчас--
                SendOutcome.Accepted(1)
            }
        }

        // Даём корутинам дойти до ожидания.
        testScheduler.advanceUntilIdle()
        assertEquals(3, пик, "одновременно должно висеть ровно три отправки")

        держим.complete(Unit)
        val обработано = проход.await()

        assertEquals(10, обработано)
        assertEquals(3, пик, "предел не должен превышаться и после разблокировки")
        assertEquals(10, store.countBy(OutboxState.SENT))
    }

    @Test
    fun упавшее_сообщение_не_останавливает_остальные() = runTest {
        // Одно неотправляемое сообщение не должно задерживать переписку — это то же
        // требование, что и «отложенная запись не загораживает готовую», но на уровне
        // прохода.
        поставить(5)

        val обработано = OutboxPump(outbox, maxConcurrent = 2).runOnce(1, запечатать) { готовое ->
            if (готовое.entry.dedupKey == "d-2") {
                SendOutcome.Permanent("подпись не сошлась")
            } else {
                SendOutcome.Accepted(1)
            }
        }

        assertEquals(5, обработано)
        assertEquals(4, store.countBy(OutboxState.SENT))
        assertEquals(1, store.countBy(OutboxState.DEAD))
    }

    @Test
    fun временные_отказы_возвращаются_в_очередь_целиком() = runTest {
        поставить(4)

        OutboxPump(outbox, maxConcurrent = 4).runOnce(1, запечатать) { SendOutcome.Retry() }

        assertEquals(4, store.countBy(OutboxState.QUEUED), "всё вернулось, ничего не потеряно")
        // Конверты остаются в памяти: эпоха не сменилась, значит повтор уйдёт теми же
        // байтами без нового запечатывания. Смену эпохи проверяет тест ниже.
        assertEquals(4, outbox.cachedEnvelopeCount())
    }

    @Test
    fun пустая_очередь_даёт_ноль_и_не_зовёт_транспорт() = runTest {
        var вызовов = 0
        val обработано = OutboxPump(outbox).runOnce(1, запечатать) {
            вызовов++
            SendOutcome.Accepted(1)
        }
        assertEquals(0, обработано)
        assertEquals(0, вызовов, "по пустой очереди в сеть ходить незачем")
    }

    @Test
    fun проход_конечен_и_не_забирает_больше_предела_партии() = runTest {
        // Проход держит запечатанные конверты в памяти, поэтому берёт ограниченную
        // партию. Очередь после долгого офлайна уйдёт за несколько проходов — это
        // правильнее, чем один проход на всю память устройства.
        поставить(OutboxPump.BATCH_LIMIT + 10)

        val первый = OutboxPump(outbox, maxConcurrent = 5).runOnce(1, запечатать) {
            SendOutcome.Accepted(1)
        }

        assertEquals(OutboxPump.BATCH_LIMIT, первый)
        assertEquals(10, store.countBy(OutboxState.QUEUED), "остаток ждёт следующего прохода")
    }

    @Test
    fun смена_эпохи_между_проходами_перезапечатывает() = runTest {
        // Ради этого запечатывание и позднее: за время ожидания в очереди ключ эпохи
        // escrow успевает смениться.
        поставить(2)
        var запечатано = 0
        val считающий: (OutboxEntry) -> ByteArray = { запечатано++; byteArrayOf(1) }

        OutboxPump(outbox, maxConcurrent = 2).runOnce(1, считающий) { SendOutcome.Retry() }
        assertEquals(2, запечатано)

        время += 1_000
        outbox.onEpochChanged(ЧАТ, 2L)
        OutboxPump(outbox, maxConcurrent = 2).runOnce(эпоха = 2, seal = считающий) {
            SendOutcome.Accepted(1)
        }

        assertEquals(4, запечатано, "под новую эпоху конверты собираются заново")
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
        поставить(1)
        assertFailsWith<IllegalStateException> {
            OutboxPump(outbox).runOnce(1, запечатать) { error("транспорт бросил") }
        }
        // И запись осталась в SENDING — её вернёт recoverOnStart, а не молчание.
        assertEquals(1, store.countBy(OutboxState.SENDING))
        assertEquals(1, outbox.recoverOnStart())
    }

    private companion object {
        const val ЧАТ = "chat-1"
    }
}
