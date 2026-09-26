package io.tima.shared

import io.tima.core.call.CallSnapshot
import io.tima.core.call.CallUpdate
import io.tima.shared.CallLedger.Action
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Лента звонков глазами телефона — ВЗ0а. Главное: изменения применяются по номеру, и
 * звонок, закончившийся в той же пачке, не звонит.
 */
class CallLedgerTest {

    private val я = "u-я"
    private val саша = "u-саша"

    private fun вызов(state: String = "ringing", from: String = саша, to: String = я) =
        CallSnapshot("c1", state = state, video = true, initiatorId = from, peerId = to)

    private fun изм(cts: Long, change: String, call: CallSnapshot = вызов(), here: Boolean = false) =
        CallUpdate(cts = cts, callId = call.callId, change = change, here = here, call = call)

    @Test
    fun вызов_звонит() {
        assertEquals(listOf(Action.Ring("c1", саша, true)), CallLedger.actions(я, listOf(изм(1, "ringing"))))
    }

    @Test
    fun отменённый_в_той_же_пачке_не_звонит_а_пропущен() {
        // Звонящий набрал и через секунду передумал — телефон собеседника забрал обе строки
        // разом. Звонить нечему; это пропущенный.
        val действия = CallLedger.actions(
            я,
            listOf(изм(1, "ringing", вызов("missed")), изм(2, "cancelled", вызов("missed"))),
        )
        assertEquals(listOf(Action.End("c1", "cancelled"), Action.Missed("c1", саша, true)), действия)
    }

    @Test
    fun мёртвый_вызов_после_офлайна_не_звонит() {
        // Телефон проспал: строка «ringing» пришла, но снимок на момент чтения — уже конец.
        assertEquals(emptyList(), CallLedger.actions(я, listOf(изм(1, "ringing", вызов("ended")))))
    }

    @Test
    fun ответили_на_другом_устройстве_замолчать() {
        val действия = CallLedger.actions(я, listOf(изм(5, "answered", вызов("answered"), here = false)))
        assertEquals(listOf(Action.Taken("c1")), действия)
    }

    @Test
    fun ответили_здесь_ничего() {
        assertEquals(emptyList(), CallLedger.actions(я, listOf(изм(5, "answered", вызов("answered"), here = true))))
    }

    @Test
    fun отклонил_сам_не_пропущенный() {
        val действия = CallLedger.actions(я, listOf(изм(3, "declined", вызов("missed"))))
        assertEquals(listOf(Action.End("c1", "declined")), действия)
    }

    @Test
    fun пропущенный_один_раз_даже_при_двух_концах() {
        val действия = CallLedger.actions(
            я,
            listOf(изм(3, "cancelled", вызов("missed")), изм(4, "missed", вызов("missed"))),
        )
        assertEquals(1, действия.count { it is Action.Missed })
    }

    @Test
    fun звонящему_доставлен_и_не_в_сети() {
        val мой = вызов(from = я, to = саша)
        val действия = CallLedger.actions(
            я,
            listOf(изм(7, "unreachable", мой), изм(8, "delivered", мой), изм(9, "declined", мой)),
        )
        assertEquals(
            listOf(Action.Unreachable("c1"), Action.Delivered("c1"), Action.End("c1", "declined")),
            действия,
        )
    }

    @Test
    fun просмотренный_снимает_строку() {
        assertEquals(listOf(Action.MissedSeen("c1")), CallLedger.actions(я, listOf(изм(10, "seen", вызов("missed")))))
    }
}
