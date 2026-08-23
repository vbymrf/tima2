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

    private var время = 1_000L
    private val store = InMemoryOutboxStore()
    private val outbox = Outbox(store, nowMs = { время })

    private val тело = byteArrayOf(1, 2, 3)

    /** Счётчик запечатываний: по нему видно, сработал ли кэш конвертов. */
    private var запечатано = 0
    private val запечатать: (OutboxEntry) -> ByteArray = {
        запечатано++
        byteArrayOf(0x10, 0x20) + it.body
    }

    private fun поставить(id: String = "dedup-1") = outbox.enqueue(id, ЧАТ, тело)

    /**
     * Запечатать в этой переписке.
     *
     * Настоящая подпись требует переписку: ключ эпохи escrow у каждой свой. Здесь
     * переписка одна, и повторять её в тридцати вызовах незачем — а вот отдельный тест
     * на ДВЕ переписки есть, и он про то, что кэш эпох у них раздельный.
     */
    private fun Outbox.sealNext(эпоха: Int, seal: (OutboxEntry) -> ByteArray) =
        sealNext(ЧАТ, эпоха.toLong(), seal)

    /** Полный путь до SENDING: запечатать под эпоху и забрать. */
    private fun довести(эпоха: Int = 1): ReadyToSend? {
        outbox.sealNext(эпоха, запечатать)
        return outbox.claimForSend()
    }

    // ── не теряет ────────────────────────────────────────────────────────────

    @Test
    fun убитое_посреди_отправки_уходит_после_перезапуска() {
        // Инвентарь, пункт 7: в v1 такое сообщение оставалось в SENDING навсегда —
        // то есть пропадало без следа для человека.
        поставить()
        assertNotNull(довести())
        assertEquals(OutboxState.SENDING, store.byDedupKey("dedup-1")?.state)

        val вернулось = outbox.recoverOnStart() // здесь процесс умер

        assertEquals(1, вернулось)
        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
        assertNotNull(довести(), "после перезапуска сообщение снова доходит до отправки")
    }

    @Test
    fun убитое_в_состоянии_запечатано_тоже_возвращается() {
        // SEALED не переживает перезапуск по определению: конверт держится в памяти.
        // Значит запись обязана вернуться в очередь, а не остаться «запечатанной» без
        // конверта — иначе она не уйдёт никогда.
        поставить()
        outbox.sealNext(1, запечатать)
        assertEquals(OutboxState.SEALED, store.byDedupKey("dedup-1")?.state)

        assertEquals(1, outbox.recoverOnStart())
        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
        assertEquals(0, outbox.cachedEnvelopeCount(), "кэш конвертов обязан быть пуст")
    }

    @Test
    fun временный_отказ_возвращает_в_очередь_а_не_теряет() {
        поставить()
        довести()
        outbox.onOutcome("dedup-1", SendOutcome.Retry())

        val запись = store.byDedupKey("dedup-1")!!
        assertEquals(OutboxState.QUEUED, запись.state)
        assertEquals(1, запись.attempts)
        assertEquals(1, запись.sealedForEpoch, "эпоха та же — конверт ещё годен")
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun запись_переживает_любое_число_перезапусков() {
        поставить()
        repeat(5) {
            довести()
            outbox.recoverOnStart()
        }
        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
        assertEquals(1, store.all().size, "перезапуски не должны размножать запись")
    }

    // ── не дублирует ─────────────────────────────────────────────────────────

    @Test
    fun повторная_постановка_того_же_ключа_не_даёт_второй_записи() {
        // Инвентарь, пункт 8: догон истории пересекается с живым каналом.
        assertTrue(поставить("dedup-1"))
        assertFalse(поставить("dedup-1"))
        assertEquals(1, store.all().size)
    }

    @Test
    fun повторная_постановка_не_сбрасывает_счётчик_попыток() {
        // Иначе неотправляемое сообщение вечно начинало бы отсчёт заново, и лестница
        // задержек никогда не доросла бы до двух минут.
        поставить()
        довести()
        outbox.onOutcome("dedup-1", SendOutcome.Retry())
        val было = store.byDedupKey("dedup-1")!!

        поставить()

        val стало = store.byDedupKey("dedup-1")!!
        assertEquals(было.attempts, стало.attempts)
        assertEquals(было.nextAttemptAtMs, стало.nextAttemptAtMs)
    }

    @Test
    fun запечатанное_второй_раз_не_берётся() {
        // Между «выбрал» и «пометил» нельзя оказаться: иначе два вызова возьмут одну
        // запись и сообщение уйдёт дважды.
        поставить()
        outbox.sealNext(1, запечатать)
        assertNotNull(outbox.claimForSend())
        assertNull(outbox.claimForSend())
        assertEquals(1, store.claims)
    }

    @Test
    fun ответ_дубликат_считается_успехом_а_не_поводом_повторять() {
        поставить()
        довести()
        outbox.onOutcome("dedup-1", SendOutcome.Duplicate(serverMessageId = 77))

        val запись = store.byDedupKey("dedup-1")!!
        assertEquals(OutboxState.SENT, запись.state)
        assertEquals(77L, запись.serverMessageId)
        assertEquals(0, outbox.pending().size)
        assertEquals(0, outbox.cachedEnvelopeCount(), "конверт отправленного не держим")
    }

    // ── позднее запечатывание и эпохи escrow ─────────────────────────────────

    @Test
    fun в_очереди_лежит_тело_а_не_конверт() {
        // Суть позднего запечатывания: пока сообщение ждёт, конверта не существует.
        поставить()
        val запись = store.byDedupKey("dedup-1")!!
        assertEquals(OutboxState.QUEUED, запись.state)
        assertNull(запись.sealedForEpoch)
        assertEquals(0, запечатано, "запечатывать до попытки отправки нельзя")
        assertEquals(0, outbox.cachedEnvelopeCount())
    }

    @Test
    fun смена_эпохи_отменяет_запечатанное() {
        // ADR-0016: за время ожидания успевает смениться ключ эпохи escrow, и заранее
        // собранный конверт унёс бы устаревший ключ — сообщение стало бы недоступно по
        // ордеру молча.
        поставить()
        outbox.sealNext(эпоха(1), запечатать)
        assertEquals(1, запечатано)

        outbox.onEpochChanged(ЧАТ, эпоха(2).toLong())

        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
        assertNull(store.byDedupKey("dedup-1")?.sealedForEpoch)
        assertEquals(0, outbox.cachedEnvelopeCount())

        // И запечатывается заново — уже под новую эпоху.
        val снова = outbox.sealNext(эпоха(2), запечатать)
        assertEquals(2, запечатано)
        assertEquals(2, снова?.sealedForEpoch)
    }

    @Test
    fun внутри_одной_эпохи_повтор_не_шифрует_заново() {
        // Кэш существует ровно для этого: повтор внутри эпохи не должен платить
        // запечатыванием заново — а это упаковка ключа на каждое устройство получателя.
        поставить()
        outbox.sealNext(1, запечатать)
        val первый = outbox.claimForSend()
        assertNotNull(первый)
        assertEquals(1, запечатано)

        // Круг целиком: сеть отказала, срок пришёл, отправляем снова.
        outbox.onOutcome("dedup-1", SendOutcome.Retry())
        время += 10_000
        val второй = outbox.sealNext(1, запечатать)

        assertEquals(1, запечатано, "конверт взят из кэша, а не собран заново")
        assertEquals(OutboxState.SEALED, второй?.state)
        assertTrue(
            первый.envelope.contentEquals(outbox.claimForSend()!!.envelope),
            "на повтор уходят те же байты: сервер опознаёт повтор по dedup_key",
        )
    }

    @Test
    fun запечатанное_под_чужую_эпоху_без_конверта_возвращается_в_очередь() {
        // Крайний случай: состояние SEALED в базе есть, конверта в кэше нет. Молча
        // отправлять нечего, и терять запись нельзя.
        поставить()
        outbox.sealNext(1, запечатать)
        outbox.recoverOnStart() // кэш пуст, запись вернулась
        store.update(store.byDedupKey("dedup-1")!!.copy(state = OutboxState.SEALED))

        assertNull(outbox.claimForSend(), "конверта нет — отправлять нечего")
        assertEquals(OutboxState.QUEUED, store.byDedupKey("dedup-1")?.state)
    }

    @Test
    fun пустой_конверт_от_запечатывания_отвергается() {
        поставить()
        assertFailsWith<IllegalArgumentException> {
            outbox.sealNext(1) { ByteArray(0) }
        }
    }

    // ── лестница задержек ────────────────────────────────────────────────────

    @Test
    fun задержки_растут_по_измеренной_лестнице_и_упираются_в_последнюю() {
        // Значения из живых испытаний v1: секунда, пять, две минуты (LinkState).
        поставить()
        for (задержка in listOf(1_000L, 5_000L, 120_000L, 120_000L)) {
            время += задержка
            assertNotNull(довести(), "на задержке $задержка запись должна была стать готовой")
            outbox.onOutcome("dedup-1", SendOutcome.Retry())
            assertEquals(время + задержка, store.byDedupKey("dedup-1")!!.nextAttemptAtMs)
        }
    }

    @Test
    fun до_срока_запись_не_запечатывается() {
        поставить()
        довести()
        outbox.onOutcome("dedup-1", SendOutcome.Retry())

        assertNull(outbox.sealNext(1, запечатать), "секунда ещё не прошла")
        время += 1_000
        assertNotNull(outbox.sealNext(1, запечатать))
    }

    @Test
    fun подсказка_сервера_сильнее_нашей_лестницы() {
        поставить()
        довести()
        outbox.onOutcome("dedup-1", SendOutcome.Retry(afterMs = 30_000))
        assertEquals(время + 30_000, store.byDedupKey("dedup-1")!!.nextAttemptAtMs)
    }

    // ── терминальные состояния и защита от неверных вызовов ──────────────────

    @Test
    fun отказ_по_сути_уводит_в_dead_и_не_повторяется() {
        поставить()
        довести()
        outbox.onOutcome("dedup-1", SendOutcome.Permanent("подпись не сошлась"))

        assertEquals(OutboxState.DEAD, store.byDedupKey("dedup-1")?.state)
        assertNull(outbox.sealNext(1, запечатать))
        assertEquals(0, outbox.pending().size, "DEAD — не «в очереди», человеку это видно иначе")
    }

    @Test
    fun результат_без_попытки_отвергается() {
        поставить()
        outbox.sealNext(1, запечатать) // SEALED, но не SENDING
        assertFailsWith<IllegalArgumentException> {
            outbox.onOutcome("dedup-1", SendOutcome.Accepted(1))
        }
    }

    @Test
    fun результат_на_чужой_ключ_отвергается() {
        поставить()
        довести()
        assertFailsWith<IllegalStateException> {
            outbox.onOutcome("dedup-которого-нет", SendOutcome.Accepted(1))
        }
    }

    @Test
    fun пустой_ключ_и_пустое_тело_не_принимаются() {
        assertFailsWith<IllegalArgumentException> { outbox.enqueue("", ЧАТ, тело) }
        assertFailsWith<IllegalArgumentException> { outbox.enqueue("dedup-2", ЧАТ, ByteArray(0)) }
    }

    // ── порядок ──────────────────────────────────────────────────────────────

    @Test
    fun очередь_отдаёт_в_порядке_постановки() {
        поставить("dedup-1")
        поставить("dedup-2")
        assertEquals("dedup-1", довести()?.entry?.dedupKey)
        outbox.onOutcome("dedup-1", SendOutcome.Accepted(1))
        assertEquals("dedup-2", довести()?.entry?.dedupKey)
    }

    @Test
    fun отложенная_запись_не_загораживает_готовую() {
        // Иначе одно неотправляемое сообщение остановило бы всю переписку.
        поставить("dedup-1")
        довести()
        outbox.onOutcome("dedup-1", SendOutcome.Retry(afterMs = 60_000))

        поставить("dedup-2")
        assertEquals("dedup-2", довести()?.entry?.dedupKey)
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
        outbox.enqueue("d-1", "chat-1", тело)
        outbox.enqueue("d-2", "chat-2", тело)

        val первое = outbox.sealNext("chat-1", 11L, запечатать)
        val второе = outbox.sealNext("chat-2", 22L, запечатать)

        assertEquals("d-1", первое?.dedupKey, "у переписки берётся ЕЁ сообщение")
        assertEquals("d-2", второе?.dedupKey)
        assertEquals(11L, первое?.sealedForEpoch)
        assertEquals(22L, второе?.sealedForEpoch)
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
        outbox.enqueue("d-1", "chat-1", тело)
        outbox.enqueue("d-2", "chat-2", тело)
        outbox.sealNext("chat-1", 11L, запечатать)
        outbox.sealNext("chat-2", 22L, запечатать)
        assertEquals(2, запечатано)

        // У первой переписки сменилась эпоха: её конверт негоден.
        outbox.sealNext("chat-1", 12L, запечатать)

        assertEquals(3, запечатано, "пересобран обязан быть ровно один конверт — свой")
        val чужое = store.byDedupKey("d-2")
        assertEquals(OutboxState.SEALED, чужое?.state, "чужое запечатанное осталось запечатанным")
        assertEquals(22L, чужое?.sealedForEpoch, "и под своей эпохой")
    }

    /** Пустая очередь этой переписки — не повод трогать чужую. */
    @Test
    fun в_чужой_переписке_запечатывать_нечего() {
        outbox.enqueue("d-1", "chat-1", тело)

        assertNull(outbox.sealNext("chat-2", 22L, запечатать), "в этой переписке нет ничего")
        assertEquals(0, запечатано)
        assertEquals(OutboxState.QUEUED, store.byDedupKey("d-1")?.state)
    }

    /** Читается как «эпоха N»: идентификатор ключа эпохи escrow. */
    private fun эпоха(n: Int) = n

    private companion object {
        const val ЧАТ = "chat-1"
    }
}
