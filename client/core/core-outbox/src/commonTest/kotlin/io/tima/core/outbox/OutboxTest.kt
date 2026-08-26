package io.tima.core.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Выход этапа К3 звучит буквально: «Outbox не теряет и не дублирует **ни в одном
 * состоянии**». Здесь это проверяется по состоянию на тест, а не одним «путь
 * туда-обратно работает».
 *
 * Часы поддельные и управляются вручную: правильность машины зависит от порядка
 * переходов и от времени, и настоящие часы сделали бы проверки то зелёными, то
 * красными без изменения кода.
 */
class OutboxTest {

    private var time = 1_000L
    private val store = InMemoryOutboxStore()
    private val outbox = Outbox(store, nowMs = { time })

    private val body = byteArrayOf(1, 2, 3)

    /** Счётчик запечатываний: по нему видно, сработал ли кэш конвертов. */
    private var sealed = 0
    private val seal: (OutboxEntry) -> ByteArray = {
        sealed++
        byteArrayOf(0x10, 0x20) + it.body
    }

    private fun put(id: String = "dedup-1") = outbox.enqueue(id, CHAT, body)

    /**
     * Запечатать в этой переписке.
     *
     * Настоящая подпись требует переписку: ключ эпохи escrow у каждой свой. Здесь
     * переписка одна, и повторять её в тридцати вызовах незачем — а вот отдельный тест
     * на ДВЕ переписки есть, и он про то, что кэш эпох у них раздельный.
     */
    private fun Outbox.sealNext(epoch: Int, seal: (OutboxEntry) -> ByteArray) =
        sealNext(CHAT, epoch.toLong(), seal)

    /** Полный путь до SENDING: запечатать под эпоху и забрать. */
    private fun deliver(epoch: Int = 1): ReadyToSend? {
        outbox.sealNext(epoch, seal)
        return outbox.claimForSend()
    }

    // ── не теряет ────────────────────────────────────────────────────────────

    @Test
    fun убитое_посреди_отправки_уходит_после_перезапуска() {
        // Инвентарь, пункт 7: в v1 такое сообщение оставалось в SENDING навсегда —
        // то есть пропадало без следа для человека.
        put()
        assertNotNull(deliver())
        assertEquals(OutboxState.SENDING, store.byDedupKey("dedup-1")?.state)

        val returned = outbox.recoverOnStart() // здесь процесс умер

        assertEquals(1, returned)
        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
        assertNotNull(deliver(), "после перезапуска сообщение снова доходит до отправки")
    }

    @Test
    fun убитое_в_состоянии_запечатано_тоже_возвращается() {
        // SEALED не переживает перезапуск по определению: конверт держится в памяти.
        // Значит запись обязана вернуться в очередь, а не остаться «запечатанной» без
        // конверта — иначе она не уйдёт никогда.
        put()
        outbox.sealNext(1, seal)
        assertEquals(OutboxState.SEALED, store.byDedupKey("dedup-1")?.state)

        assertEquals(1, outbox.recoverOnStart())
        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
        assertEquals(0, outbox.cachedEnvelopeCount(), "кэш конвертов обязан быть пуст")
    }

    @Test
    fun временный_отказ_возвращает_в_очередь_а_не_теряет() {
        put()
        deliver()
        outbox.onOutcome("dedup-1", SendOutcome.Retry())

        val entry = store.byDedupKey("dedup-1")!!
        assertEquals(OutboxState.QUEUED, entry.state)
        assertEquals(1, entry.attempts)
        assertEquals(1, entry.sealedForEpoch, "эпоха та же — конверт ещё годен")
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun запись_переживает_любое_число_перезапусков() {
        put()
        repeat(5) {
            deliver()
            outbox.recoverOnStart()
        }
        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
        assertEquals(1, store.all().size, "перезапуски не должны размножать запись")
    }

    // ── не дублирует ─────────────────────────────────────────────────────────

    @Test
    fun повторная_постановка_того_же_ключа_не_даёт_второй_записи() {
        // Инвентарь, пункт 8: догон истории пересекается с живым каналом.
        assertTrue(put("dedup-1"))
        assertFalse(put("dedup-1"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun повторная_постановка_не_сбрасывает_счётчик_попыток() {
        // Иначе неотправляемое сообщение вечно начинало бы отсчёт заново, и лестница
        // задержек никогда не доросла бы до двух минут.
        put()
        deliver()
        outbox.onOutcome("dedup-1", SendOutcome.Retry())
        val was = store.byDedupKey("dedup-1")!!

        put()

        val became = store.byDedupKey("dedup-1")!!
        assertEquals(was.attempts, became.attempts)
        assertEquals(was.nextAttemptAtMs, became.nextAttemptAtMs)
    }

    @Test
    fun запечатанное_второй_раз_не_берётся() {
        // Между «выбрал» и «пометил» нельзя оказаться: иначе два вызова возьмут одну
        // запись и сообщение уйдёт дважды.
        put()
        outbox.sealNext(1, seal)
        assertNotNull(outbox.claimForSend())
        assertNull(outbox.claimForSend())
        assertEquals(1, store.claims)
    }

    @Test
    fun ответ_дубликат_считается_успехом_а_не_поводом_повторять() {
        put()
        deliver()
        outbox.onOutcome("dedup-1", SendOutcome.Duplicate(serverMessageId = 77))

        val entry = store.byDedupKey("dedup-1")!!
        assertEquals(OutboxState.SENT, entry.state)
        assertEquals(77L, entry.serverMessageId)
        assertEquals(0, outbox.pending().size)
        assertEquals(0, outbox.cachedEnvelopeCount(), "конверт отправленного не держим")
    }

    // ── позднее запечатывание и эпохи escrow ─────────────────────────────────

    @Test
    fun в_очереди_лежит_тело_а_не_конверт() {
        // Суть позднего запечатывания: пока сообщение ждёт, конверта не существует.
        put()
        val entry = store.byDedupKey("dedup-1")!!
        assertEquals(OutboxState.QUEUED, entry.state)
        assertNull(entry.sealedForEpoch)
        assertEquals(0, sealed, "запечатывать до попытки отправки нельзя")
        assertEquals(0, outbox.cachedEnvelopeCount())
    }

    @Test
    fun смена_эпохи_отменяет_запечатанное() {
        // ADR-0016: за время ожидания успевает смениться ключ эпохи escrow, и заранее
        // собранный конверт унёс бы устаревший ключ — сообщение стало бы недоступно по
        // ордеру молча.
        put()
        outbox.sealNext(epoch(1), seal)
        assertEquals(1, sealed)

        outbox.onEpochChanged(CHAT, epoch(2).toLong())

        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
        assertNull(store.byDedupKey("dedup-1")?.sealedForEpoch)
        assertEquals(0, outbox.cachedEnvelopeCount())

        // И запечатывается заново — уже под новую эпоху.
        val again = outbox.sealNext(epoch(2), seal)
        assertEquals(2, sealed)
        assertEquals(2, again?.sealedForEpoch)
    }

    @Test
    fun внутри_одной_эпохи_повтор_не_шифрует_заново() {
        // Кэш существует ровно для этого: повтор внутри эпохи не должен платить
        // запечатыванием заново — а это упаковка ключа на каждое устройство получателя.
        put()
        outbox.sealNext(1, seal)
        val first = outbox.claimForSend()
        assertNotNull(first)
        assertEquals(1, sealed)

        // Круг целиком: сеть отказала, срок пришёл, отправляем снова.
        outbox.onOutcome("dedup-1", SendOutcome.Retry())
        time += 10_000
        val second = outbox.sealNext(1, seal)

        assertEquals(1, sealed, "конверт взят из кэша, а не собран заново")
        assertEquals(OutboxState.SEALED, second?.state)
        assertTrue(
            first.envelope.contentEquals(outbox.claimForSend()!!.envelope),
            "на повтор уходят те же байты: сервер опознаёт повтор по dedup_key",
        )
    }

    @Test
    fun запечатанное_под_чужую_эпоху_без_конверта_возвращается_в_очередь() {
        // Крайний случай: состояние SEALED в базе есть, конверта в кэше нет. Молча
        // отправлять нечего, и терять запись нельзя.
        put()
        outbox.sealNext(1, seal)
        outbox.recoverOnStart() // кэш пуст, запись вернулась
        store.update(store.byDedupKey("dedup-1")!!.copy(state = OutboxState.SEALED))

        assertNull(outbox.claimForSend(), "конверта нет — отправлять нечего")
        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
    }

    @Test
    fun пустой_конверт_от_запечатывания_отвергается() {
        put()
        assertFailsWith<IllegalArgumentException> {
            outbox.sealNext(1) { ByteArray(0) }
        }
    }

    // ── лестница задержек ────────────────────────────────────────────────────

    @Test
    fun задержки_растут_по_измеренной_лестнице_и_упираются_в_последнюю() {
        // Значения из живых испытаний v1: секунда, пять, две минуты (LinkState).
        put()
        for (delay in listOf(1_000L, 5_000L, 120_000L, 120_000L)) {
            time += delay
            assertNotNull(deliver(), "на задержке $delay запись должна была стать готовой")
            outbox.onOutcome("dedup-1", SendOutcome.Retry())
            assertEquals(time + delay, store.byDedupKey("dedup-1")!!.nextAttemptAtMs)
        }
    }

    @Test
    fun до_срока_запись_не_запечатывается() {
        put()
        deliver()
        outbox.onOutcome("dedup-1", SendOutcome.Retry())

        assertNull(outbox.sealNext(1, seal), "секунда ещё не прошла")
        time += 1_000
        assertNotNull(outbox.sealNext(1, seal))
    }

    @Test
    fun подсказка_сервера_сильнее_нашей_лестницы() {
        put()
        deliver()
        outbox.onOutcome("dedup-1", SendOutcome.Retry(afterMs = 30_000))
        assertEquals(time + 30_000, store.byDedupKey("dedup-1")!!.nextAttemptAtMs)
    }

    // ── терминальные состояния и защита от неверных вызовов ──────────────────

    @Test
    fun отказ_по_сути_уводит_в_dead_и_не_повторяется() {
        put()
        deliver()
        outbox.onOutcome("dedup-1", SendOutcome.Permanent("подпись не сошлась"))

        assertEquals(OutboxState.DEAD, store.byDedupKey("dedup-1")?.state)
        assertNull(outbox.sealNext(1, seal))
        assertEquals(0, outbox.pending().size, "DEAD — не «в очереди», человеку это видно иначе")
    }

    @Test
    fun результат_без_попытки_отвергается() {
        put()
        outbox.sealNext(1, seal) // SEALED, но не SENDING
        assertFailsWith<IllegalArgumentException> {
            outbox.onOutcome("dedup-1", SendOutcome.Accepted(1))
        }
    }

    @Test
    fun результат_на_чужой_ключ_отвергается() {
        put()
        deliver()
        assertFailsWith<IllegalStateException> {
            outbox.onOutcome("dedup-которого-нет", SendOutcome.Accepted(1))
        }
    }

    @Test
    fun пустой_ключ_и_пустое_тело_не_принимаются() {
        assertFailsWith<IllegalArgumentException> { outbox.enqueue("", CHAT, body) }
        assertFailsWith<IllegalArgumentException> { outbox.enqueue("dedup-2", CHAT, ByteArray(0)) }
    }

    // ── порядок ──────────────────────────────────────────────────────────────

    @Test
    fun очередь_отдаёт_в_порядке_постановки() {
        put("dedup-1")
        put("dedup-2")
        assertEquals("dedup-1", deliver()?.entry?.dedupKey)
        outbox.onOutcome("dedup-1", SendOutcome.Accepted(1))
        assertEquals("dedup-2", deliver()?.entry?.dedupKey)
    }

    @Test
    fun отложенная_запись_не_загораживает_готовую() {
        // Иначе одно неотправляемое сообщение остановило бы всю переписку.
        put("dedup-1")
        deliver()
        outbox.onOutcome("dedup-1", SendOutcome.Retry(afterMs = 60_000))

        put("dedup-2")
        assertEquals("dedup-2", deliver()?.entry?.dedupKey)
    }


    // ── две переписки: у каждой своя эпоха ──────────────────────────────────

    /**
     * **У каждой переписки своя эпоха escrow, и очередь это различает.**
     *
     * Ключ эпохи спрашивается у сервера по `chat_id` (`GET /api/v1/escrow/key`). В первой
     * редакции очередь принимала одну эпоху на всё, и с двумя переписками это означало
     * `sealedForEpoch`, записанный числом чужой переписки. Пока переписка была одна,
     * разницы не было видно вовсе — нашлось при подключении отправки к приложению, где
     * переписок сразу много.
     */
    @Test
    fun каждая_переписка_запечатывается_своей_эпохой() {
        outbox.enqueue("d-1", "chat-1", body)
        outbox.enqueue("d-2", "chat-2", body)

        val first = outbox.sealNext("chat-1", 11L, seal)
        val second = outbox.sealNext("chat-2", 22L, seal)

        assertEquals("d-1", first?.dedupKey, "у переписки берётся ЕЁ сообщение")
        assertEquals("d-2", second?.dedupKey)
        assertEquals(11L, first?.sealedForEpoch)
        assertEquals(22L, second?.sealedForEpoch)
        assertEquals(2, outbox.cachedEnvelopeCount())
    }

    /**
     * Смена эпохи в одной переписке **не трогает** конверты другой.
     *
     * Иначе каждое переключение между двумя переписками пересобирало бы конверты обеих —
     * а конверт это тело плюс обёртка ключа на каждое устройство получателя, то есть самая
     * дорогая операция отправки.
     */
    @Test
    fun смена_эпохи_в_одной_переписке_не_пересобирает_чужие_конверты() {
        outbox.enqueue("d-1", "chat-1", body)
        outbox.enqueue("d-2", "chat-2", body)
        outbox.sealNext("chat-1", 11L, seal)
        outbox.sealNext("chat-2", 22L, seal)
        assertEquals(2, sealed)

        // У первой переписки сменилась эпоха: её конверт негоден.
        outbox.sealNext("chat-1", 12L, seal)

        assertEquals(3, sealed, "пересобран обязан быть ровно один конверт — свой")
        val foreign = store.byDedupKey("d-2")
        assertEquals(OutboxState.SEALED, foreign?.state, "чужое запечатанное осталось запечатанным")
        assertEquals(22L, foreign?.sealedForEpoch, "и под своей эпохой")
    }

    /** Пустая очередь этой переписки — не повод трогать чужую. */
    @Test
    fun в_чужой_переписке_запечатывать_нечего() {
        outbox.enqueue("d-1", "chat-1", body)

        assertNull(outbox.sealNext("chat-2", 22L, seal), "в этой переписке нет ничего")
        assertEquals(0, sealed)
        assertEquals(OutboxState.QUEUED, store.byDedupKey("d-1")?.state)
    }

    /** Читается как «эпоха N»: идентификатор ключа эпохи escrow. */
    private fun epoch(n: Int) = n

    private companion object {
        const val CHAT = "chat-1"
    }
}
