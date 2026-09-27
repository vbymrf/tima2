package io.tima.shared

import io.tima.shared.EventKind.Battery
import io.tima.shared.EventKind.Calls
import io.tima.shared.EventKind.Installed
import io.tima.shared.EventKind.Notices
import io.tima.shared.EventKind.Update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Очередь событий — ПЛАН-СОБЫТИЙ §0: по проверке на каждую беду, из-за которой очередь
 * заведена, и на решения заказчика 2026-09-27.
 */
class EventQueueTest {

    private fun есть(vararg kinds: EventKind): Map<EventKind, Presence> =
        EventKind.entries.associateWith { if (it in kinds) Presence.Yes else Presence.No }

    @Test
    fun порядок_важности_от_заказчика() {
        val вид = EventQueue.view(есть(Installed, Battery, Update, Calls, Notices), EventMemory(), allowed = true)
        assertEquals(listOf(Update, Notices, Calls, Battery, Installed), вид.waiting)
        assertEquals(Update, вид.current)
        assertEquals(1, вид.position)
        assertEquals(5, вид.total)
    }

    @Test
    fun не_знаю_не_показывается() {
        // Redmi, QN4N: беда известна сразу, «Позже» дочитывается через 0,1 с. Пока не
        // дочитано — событие не существует, а не «показано на всякий случай».
        val сведения = есть().plus(Battery to Presence.Unknown)
        val вид = EventQueue.view(сведения, EventMemory(), allowed = true)
        assertNull(вид.current)
        assertTrue(вид.waiting.isEmpty())
    }

    @Test
    fun опоздавшее_важное_не_подменяет_открытое() {
        // Батарея на экране; через две секунды с сервера приходит важное обновление.
        var память = EventMemory()
        val сначала = EventQueue.view(есть(Battery), память, allowed = true)
        память = EventQueue.shown(память, сначала)

        val потом = EventQueue.view(есть(Battery, Update), память, allowed = true)
        assertEquals(Battery, потом.current, "открытое вытеснено опоздавшим")
        assertEquals(listOf(EventLine(Update, fresh = true)), потом.rest, "опоздавшее не встало в список с пометкой «новое»")
    }

    @Test
    fun бывшее_при_открытии_не_новое() {
        var память = EventMemory()
        val вид = EventQueue.view(есть(Notices, Battery), память, allowed = true)
        память = EventQueue.shown(память, вид)
        assertEquals(listOf(EventLine(Battery, fresh = false)), EventQueue.view(есть(Notices, Battery), память, true).rest)
    }

    @Test
    fun исправленное_уходит_само() {
        var память = EventMemory()
        память = EventQueue.shown(память, EventQueue.view(есть(Notices, Battery), память, true))
        // Включили уведомления в системе и вернулись — строки нет, открыта следующая.
        val вид = EventQueue.view(есть(Battery), память, allowed = true)
        assertEquals(Battery, вид.current)
        assertTrue(вид.rest.isEmpty())
    }

    @Test
    fun закрытое_не_возвращается_после_переписки_и_звонка() {
        var память = EventMemory()
        память = EventQueue.shown(память, EventQueue.view(есть(Battery), память, true))
        память = EventQueue.close(память, Battery)
        // Ушёл в переписку (нельзя показывать), вернулся (можно) — беда всё ещё есть.
        assertNull(EventQueue.view(есть(Battery), память, allowed = false).current)
        assertNull(EventQueue.view(есть(Battery), память, allowed = true).current, "закрытое вернулось")
    }

    @Test
    fun нельзя_показывать_очередь_ждёт_и_ничего_не_теряет() {
        val память = EventMemory()
        val вовремязвонка = EventQueue.view(есть(Update), память, allowed = false)
        assertNull(вовремязвонка.current)
        assertEquals(listOf(Update), вовремязвонка.waiting)
        assertEquals(Update, EventQueue.view(есть(Update), память, allowed = true).current)
    }

    @Test
    fun следующее_закрывает_текущее_и_двигает_счёт() {
        var память = EventMemory()
        память = EventQueue.shown(память, EventQueue.view(есть(Update, Battery, Installed), память, true))
        память = EventQueue.close(память, Update)
        val вид = EventQueue.view(есть(Update, Battery, Installed), память, allowed = true)
        assertEquals(Battery, вид.current)
        assertEquals(2, вид.position)
        assertEquals(3, вид.total)
    }

    @Test
    fun открыть_из_списка_не_теряет_прежнее() {
        var память = EventMemory()
        память = EventQueue.shown(память, EventQueue.view(есть(Update, Battery), память, true))
        память = EventQueue.open(память, Battery)
        val вид = EventQueue.view(есть(Update, Battery), память, allowed = true)
        assertEquals(Battery, вид.current)
        assertEquals(listOf(Update), вид.rest.map { it.kind }, "прежнее открытое пропало из списка")
    }

    @Test
    fun пропустить_все_до_следующего_запуска() {
        var память = EventMemory()
        память = EventQueue.shown(память, EventQueue.view(есть(Update, Battery), память, true))
        память = EventQueue.skipAll(память)
        // Встало новое — всё равно тихо: пропущено всё, что будет до следующего запуска.
        assertNull(EventQueue.view(есть(Update, Battery, Installed), память, allowed = true).current)
        // Следующий запуск — новая память, и важное обновление снова первое.
        assertEquals(Update, EventQueue.view(есть(Update, Battery), EventMemory(), true).current)
    }
}
