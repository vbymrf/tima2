package io.tima.core.outbox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Вторая половина К3.6: входящая машина. Проверяется по состоянию на тест, как и
 * исходящая.
 *
 * Главное свойство здесь одно: **конверт записывается до попытки разбора.** Разбор
 * падает по многим причинам, и если сначала разбирать, а писать потом, каждое такое
 * падение теряет сообщение безвозвратно — живой канал его больше не пришлёт.
 */
class InboxTest {

    private var время = 1_000L
    private val store = InMemoryInboxStore()
    private val inbox = Inbox(store, nowMs = { время })

    private val конверт = byteArrayOf(9, 9, 9)
    private val тело = byteArrayOf(1, 2)

    /** Куда ложится разобранное; заодно счётчик — по нему видно лишние записи. */

    private fun принять(id: Long = 5) = inbox.receive("chat-1", id, конверт)

    // ── приём идемпотентен ───────────────────────────────────────────────────

    @Test
    fun то_же_сообщение_из_живого_канала_и_из_догона_даёт_одну_запись() {
        // Инвентарь, пункт 8: догон истории пересекается с тем, что уже пришло по WS.
        // Идентификатор назначает отправитель, и он входит в подпись — подделать
        // нельзя, значит по нему и опознаём повтор.
        assertTrue(принять(5))
        assertFalse(принять(5), "повтор — не новая запись")
        assertEquals(1, store.all().size)
    }

    @Test
    fun разные_чаты_с_одинаковым_номером_не_путаются() {
        // Идентификатор сообщения уникален в пределах чата, а не глобально: ключ
        // составной, и без chatId второе сообщение затёрло бы первое.
        assertTrue(inbox.receive("chat-1", 5, конверт))
        assertTrue(inbox.receive("chat-2", 5, конверт))
        assertEquals(2, store.all().size)
    }

    @Test
    fun конверт_лежит_записанным_до_разбора() {
        принять()
        val e = store.byKey("chat-1", 5)
        assertNotNull(e)
        assertEquals(IncomingState.RECEIVED, e.state)
        assertTrue(конверт.contentEquals(e.envelope), "исходник нужен второй попытке")
        assertEquals(null, store.body("chat-1", 5), "разбирать до записи нельзя")
    }

    // ── разбор ──────────────────────────────────────────────────────────────

    @Test
    fun разобранное_становится_сохранённым() {
        принять()
        val после = inbox.openNext({ OpenOutcome.Opened(тело) })

        assertEquals(IncomingState.STORED, после?.state)
        assertTrue(тело.contentEquals(store.body("chat-1", 5) ?: ByteArray(0)))
        assertNull(inbox.openNext({ OpenOutcome.Opened(тело) }), "разбирать больше нечего")
    }

    @Test
    fun нет_ключа_не_теряет_сообщение_а_помечает_его() {
        // ADR: UNDECRYPTABLE — состояние, а не потеря. Ключ может приехать позже:
        // обёртка для этого устройства ещё не пришла, групповой ключ ротировался,
        // история опередила ключи.
        принять()
        val после = inbox.openNext({ OpenOutcome.NoKey("обёртки для устройства нет") })

        assertEquals(IncomingState.UNDECRYPTABLE, после?.state)
        assertEquals(1, после?.attempts)
        assertEquals("обёртки для устройства нет", после?.undecryptableReason)
        assertEquals(1, inbox.pending().size, "нечитаемое остаётся видимым")
        assertTrue(конверт.contentEquals(store.byKey("chat-1", 5)!!.envelope))
    }

    @Test
    fun появился_ключ_и_нечитаемое_разбирается_снова() {
        принять()
        inbox.openNext({ OpenOutcome.NoKey("нет ключа") })

        assertEquals(1, inbox.retryUndecryptable(), "вернуться должно одно")
        assertEquals(IncomingState.RECEIVED, store.byKey("chat-1", 5)?.state)

        val после = inbox.openNext({ OpenOutcome.Opened(тело) })
        assertEquals(IncomingState.STORED, после?.state)
        assertTrue(тело.contentEquals(store.body("chat-1", 5)!!))
    }

    @Test
    fun отвергнутый_конверт_остаётся_видимым_а_не_исчезает() {
        // Молчаливое исчезновение — худший вариант: подмена становится незаметной.
        // Человек должен видеть, что сообщение было и что оно не прошло проверку.
        принять()
        val после = inbox.openNext({ OpenOutcome.Rejected("подпись не сошлась") })

        assertEquals(IncomingState.UNDECRYPTABLE, после?.state)
        assertEquals("подпись не сошлась", после?.undecryptableReason)
        assertEquals(1, store.all().size)
        assertEquals(null, store.body("chat-1", 5), "содержимое отвергнутого не записывается")
    }

    @Test
    fun падение_записи_содержимого_оставляет_сообщение_на_разбор() {
        // Порядок «записать содержимое, потом сменить состояние» проверяется именно
        // так: если запись упала, состояние обязано остаться RECEIVED.
        принять()
        assertFailsWith<IllegalStateException> {
            store.падатьНаЗаписиТела = true
            try {
                inbox.openNext { OpenOutcome.Opened(тело) }
            } finally {
                store.падатьНаЗаписиТела = false
            }
        }
        assertEquals(IncomingState.RECEIVED, store.byKey("chat-1", 5)?.state)
        assertNotNull(inbox.openNext({ OpenOutcome.Opened(тело) }), "разбирается снова")
    }

    @Test
    fun повторные_неудачи_копят_счётчик_а_не_обнуляют_его() {
        // По счётчику видно, что сообщение висит давно, — это и показывается человеку
        // как «не удаётся прочитать», а не молчание.
        принять()
        repeat(3) {
            inbox.openNext({ OpenOutcome.NoKey("нет ключа") })
            inbox.retryUndecryptable()
        }
        inbox.openNext({ OpenOutcome.NoKey("нет ключа") })
        assertEquals(4, store.byKey("chat-1", 5)?.attempts)
    }

    // ── прочтение ───────────────────────────────────────────────────────────

    @Test
    fun прочитанным_становится_только_разобранное() {
        принять()
        // Пока не разобрано — читать нечего, и это ошибка вызывающего.
        assertFailsWith<IllegalArgumentException> { inbox.markRead("chat-1", 5) }

        inbox.openNext({ OpenOutcome.Opened(тело) })
        inbox.markRead("chat-1", 5)

        assertEquals(IncomingState.READ, store.byKey("chat-1", 5)?.state)
        assertEquals(0, inbox.pending().size, "прочитанное не «в работе»")
    }

    @Test
    fun прочтение_несуществующего_отвергается() {
        assertFailsWith<IllegalStateException> { inbox.markRead("chat-1", 404) }
    }

    // ── защита входа ────────────────────────────────────────────────────────

    @Test
    fun пустые_и_неположительные_значения_не_принимаются() {
        assertFailsWith<IllegalArgumentException> { inbox.receive("", 5, конверт) }
        // Ноль в v1 ездил в том же поле, что настоящий идентификатор, и означал «ещё
        // не отправлено». Здесь такого не будет: отсутствие значения не притворяется
        // значением.
        assertFailsWith<IllegalArgumentException> { inbox.receive("chat-1", 0, конверт) }
        assertFailsWith<IllegalArgumentException> { inbox.receive("chat-1", 5, ByteArray(0)) }
    }
}
