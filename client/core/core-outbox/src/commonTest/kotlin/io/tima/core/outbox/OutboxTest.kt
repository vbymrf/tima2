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
 * состоянии**». Здесь это и проверяется — по состоянию на тест, а не одним «путь
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

    private val конверт = byteArrayOf(1, 2, 3)

    private fun поставить(id: String = "cmid-1") =
        outbox.enqueue(id, chatId = "chat-1", envelope = конверт)

    // ── не теряет ────────────────────────────────────────────────────────────

    @Test
    fun убитое_посреди_отправки_уходит_после_перезапуска() {
        // Инвентарь, пункт 7: в v1 такое сообщение оставалось в SENDING навсегда —
        // то есть пропадало без следа для человека.
        поставить()
        val взято = outbox.next()
        assertNotNull(взято)
        assertEquals(OutboxState.SENDING, store.byClientMsgId("cmid-1")?.state)

        // Здесь процесс умирает: результата попытки нет и не будет.
        val вернулось = outbox.recoverOnStart()

        assertEquals(1, вернулось, "зависшее в SENDING обязано вернуться в очередь")
        assertEquals(OutboxState.QUEUED, store.byClientMsgId("cmid-1")?.state)
        assertNotNull(outbox.next(), "после перезапуска сообщение снова готово к отправке")
    }

    @Test
    fun временный_отказ_возвращает_в_очередь_а_не_теряет() {
        поставить()
        outbox.next()
        outbox.onOutcome("cmid-1", SendOutcome.Retry(afterMs = 0))

        val запись = store.byClientMsgId("cmid-1")!!
        assertEquals(OutboxState.QUEUED, запись.state)
        assertEquals(1, запись.attempts)
        assertEquals(1, outbox.pending().size)
    }

    @Test
    fun запись_в_очереди_переживает_любое_число_перезапусков() {
        поставить()
        repeat(5) {
            outbox.next()
            outbox.recoverOnStart() // падение до получения результата
        }
        assertEquals(OutboxState.QUEUED, store.byClientMsgId("cmid-1")?.state)
        assertEquals(1, store.all().size, "перезапуски не должны размножать запись")
    }

    // ── не дублирует ─────────────────────────────────────────────────────────

    @Test
    fun повторная_постановка_того_же_идентификатора_не_даёт_второй_записи() {
        // Инвентарь, пункт 8: догон истории пересекается с живым каналом, и без
        // уникальности своего идентификатора это давало дубль.
        assertTrue(поставить("cmid-1"))
        assertFalse(поставить("cmid-1"), "второй раз — не поставлено")
        assertEquals(1, store.all().size)
    }

    @Test
    fun повторная_постановка_не_сбрасывает_счётчик_попыток() {
        // Иначе сообщение, которое не уходит, вечно начинало бы отсчёт заново — и
        // лестница задержек никогда не доросла бы до двух минут.
        поставить()
        outbox.next()
        outbox.onOutcome("cmid-1", SendOutcome.Retry(afterMs = 0))
        val было = store.byClientMsgId("cmid-1")!!

        поставить() // повтор
        val стало = store.byClientMsgId("cmid-1")!!

        assertEquals(было.attempts, стало.attempts)
        assertEquals(было.nextAttemptAtMs, стало.nextAttemptAtMs)
    }

    @Test
    fun взятое_на_отправку_второй_раз_не_берётся() {
        // Между «выбрал» и «пометил» нельзя оказаться: иначе два вызова возьмут одну
        // запись, и сообщение уйдёт дважды.
        поставить()
        assertNotNull(outbox.next())
        assertNull(outbox.next(), "запись в SENDING не должна выдаваться снова")
        assertEquals(1, store.claims)
    }

    @Test
    fun ответ_дубликат_считается_успехом_а_не_поводом_повторять() {
        // Сервер дедуплицирует по client_msg_id. Если считать это ошибкой, клиент
        // будет повторять вечно сообщение, которое давно дошло.
        поставить()
        outbox.next()
        outbox.onOutcome("cmid-1", SendOutcome.Duplicate(serverMessageId = 77))

        val запись = store.byClientMsgId("cmid-1")!!
        assertEquals(OutboxState.SENT, запись.state)
        assertEquals(77L, запись.serverMessageId)
        assertNull(outbox.next(), "отправленное больше не берётся")
        assertEquals(0, outbox.pending().size)
    }

    // ── лестница задержек ────────────────────────────────────────────────────

    @Test
    fun задержки_растут_по_измеренной_лестнице_и_упираются_в_последнюю() {
        // Значения из живых испытаний v1: секунда, пять, две минуты (LinkState).
        поставить()
        val ожидаемые = listOf(1_000L, 5_000L, 120_000L, 120_000L)
        for (задержка in ожидаемые) {
            время += задержка // дождались прошлой
            val взято = outbox.next()
            assertNotNull(взято, "на задержке $задержка запись должна была стать готовой")
            outbox.onOutcome("cmid-1", SendOutcome.Retry(afterMs = 0))
            assertEquals(
                время + задержка,
                store.byClientMsgId("cmid-1")!!.nextAttemptAtMs,
                "неверная задержка после ${store.byClientMsgId("cmid-1")!!.attempts} попыток",
            )
        }
    }

    @Test
    fun до_срока_запись_не_выдаётся() {
        поставить()
        outbox.next()
        outbox.onOutcome("cmid-1", SendOutcome.Retry(afterMs = 0))

        assertNull(outbox.next(), "секунда ещё не прошла")
        время += 1_000
        assertNotNull(outbox.next())
    }

    @Test
    fun подсказка_сервера_сильнее_нашей_лестницы() {
        // Retry-After: сервер знает про свою перегрузку больше нас.
        поставить()
        outbox.next()
        outbox.onOutcome("cmid-1", SendOutcome.Retry(afterMs = 30_000))
        assertEquals(время + 30_000, store.byClientMsgId("cmid-1")!!.nextAttemptAtMs)
    }

    // ── терминальные состояния и защита от неверных вызовов ──────────────────

    @Test
    fun отказ_по_сути_не_повторяется() {
        поставить()
        outbox.next()
        outbox.onOutcome("cmid-1", SendOutcome.Permanent("подпись не сошлась"))

        assertEquals(OutboxState.FAILED, store.byClientMsgId("cmid-1")?.state)
        assertNull(outbox.next())
        assertEquals(0, outbox.pending().size, "FAILED — не «в очереди», человеку это видно иначе")
    }

    @Test
    fun результат_без_попытки_отвергается() {
        // Результат приходит только на то, что было взято. Иначе запись, лежащая в
        // очереди, могла бы «стать отправленной» из-за путаницы в идентификаторах.
        поставить()
        assertFailsWith<IllegalArgumentException> {
            outbox.onOutcome("cmid-1", SendOutcome.Accepted(1))
        }
    }

    @Test
    fun результат_на_чужой_идентификатор_отвергается() {
        поставить()
        outbox.next()
        assertFailsWith<IllegalStateException> {
            outbox.onOutcome("cmid-которого-нет", SendOutcome.Accepted(1))
        }
    }

    @Test
    fun пустой_идентификатор_и_пустой_конверт_не_принимаются() {
        // Пустой clientMsgId лишает сервер возможности опознать повтор, а пустой
        // конверт — это отправка ничего, которую заметят только у получателя.
        assertFailsWith<IllegalArgumentException> { outbox.enqueue("", "chat-1", конверт) }
        assertFailsWith<IllegalArgumentException> { outbox.enqueue("cmid-2", "chat-1", ByteArray(0)) }
    }

    // ── порядок ──────────────────────────────────────────────────────────────

    @Test
    fun очередь_отдаёт_в_порядке_постановки() {
        поставить("cmid-1")
        поставить("cmid-2")
        поставить("cmid-3")

        assertEquals("cmid-1", outbox.next()?.clientMsgId)
        assertEquals("cmid-2", outbox.next()?.clientMsgId)
        assertEquals("cmid-3", outbox.next()?.clientMsgId)
    }

    @Test
    fun отложенная_запись_не_загораживает_готовую() {
        // Иначе одно неотправляемое сообщение остановило бы всю переписку.
        поставить("cmid-1")
        outbox.next()
        outbox.onOutcome("cmid-1", SendOutcome.Retry(afterMs = 60_000))

        поставить("cmid-2")
        assertEquals("cmid-2", outbox.next()?.clientMsgId)
    }
}
