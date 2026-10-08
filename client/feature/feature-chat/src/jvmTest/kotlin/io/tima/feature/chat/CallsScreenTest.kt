package io.tima.feature.chat

import io.tima.domain.chat.CallRecord
import io.tima.domain.chat.ChatPerson
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Вкладка «Звонки»: групповой звонок — своей строкой с «ГЗ» (заказчик 2026-10-08: из «Чатов»
 * группы звонка убраны), и короткое слово поверх журнала — «Чат удалён».
 */
class CallsScreenTest {

    private val me = "me"
    private val direct = CallRecord(
        callId = "c1", video = false, state = "ended", initiatorId = me, peerId = "u1",
        createdAt = 1_700_000_000_000, answeredAt = 1_700_000_005_000, endedAt = 1_700_000_065_000,
    )
    private val group = direct.copy(callId = "c2", video = true, peerId = "", groupId = "g1")

    @Test
    fun групповой_звонок_строкой_с_гз() {
        val asGroup = capture("звонки-групповой", 400, 300, dark = false) {
            CallsScreen(
                records = listOf(group), me = me, personOf = { ChatPerson(name = "Аня") }, onCallAgain = {},
                groupOf = { GroupCallLine(face = null) },
            )
        }
        val asPerson = capture("звонки-личный", 400, 300, dark = false) {
            CallsScreen(records = listOf(direct), me = me, personOf = { ChatPerson(name = "Аня") }, onCallAgain = {})
        }
        assertTrue(asGroup.difference(asPerson) > 0.005, "групповая строка выглядит как личная")
    }

    @Test
    fun звонок_с_уведомлением_в_оранжевом_контуре() {
        // Вход во вкладку уведомление снимает; кто его оставил, видно по контуру аватара.
        val missed = direct.copy(callId = "c3", state = "missed", initiatorId = "u1", peerId = me, answeredAt = 0, endedAt = 0)
        val flagged = capture("звонки-контур", 400, 300, dark = false) {
            CallsScreen(records = listOf(missed), me = me, personOf = { ChatPerson(name = "Аня", phone = "+7 900 000-00-00") }, flagged = { true })
        }
        val plain = capture("звонки-без-контура", 400, 300, dark = false) {
            CallsScreen(records = listOf(missed), me = me, personOf = { ChatPerson(name = "Аня", phone = "+7 900 000-00-00") })
        }
        assertTrue(flagged.difference(plain) > 0.001, "контура нет")
    }

    @Test
    fun слово_поверх_журнала() {
        val with = capture("звонки-чат-удалён", 400, 300, dark = false) {
            CallsScreen(records = listOf(direct), me = me, personOf = { ChatPerson(name = "Аня") }, note = "Чат удалён")
        }
        val without = capture("звонки-без-слова", 400, 300, dark = false) {
            CallsScreen(records = listOf(direct), me = me, personOf = { ChatPerson(name = "Аня") })
        }
        assertTrue(with.difference(without) > 0.005, "«Чат удалён» не показан")
    }
}
