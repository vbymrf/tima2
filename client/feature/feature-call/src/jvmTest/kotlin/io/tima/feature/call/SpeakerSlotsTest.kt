package io.tima.feature.call

import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.RoomMeta
import io.tima.testui.capture
import kotlin.test.Test
import kotlin.test.assertEquals

/** Вид «Говорящий» (заказчик 2026-10-01): правило двух мест наверху. */
class SpeakerSlotsTest {

    private val order = listOf("a", "b", "c", "d", "me")

    @Test
    fun пока_никто_не_говорил_места_заняты_первыми_по_входу() {
        assertEquals(listOf("a", "b"), SpeakerSlots().update(order, emptySet(), null, 0))
    }

    @Test
    fun наверх_через_секунду_речи_кашель_не_считается() {
        val s = SpeakerSlots()
        s.update(order, emptySet(), null, 0)
        s.update(order, setOf("c"), null, 10_000)
        assertEquals(listOf("a", "b"), s.update(order, setOf("c"), null, 10_500), "полсекунды — ещё рано")
        assertEquals("c", s.update(order, setOf("c"), null, 11_100).first { it == "c" })
    }

    @Test
    fun вытесняется_молчащий_а_не_говорящий_местами_не_меняем() {
        val s = SpeakerSlots()
        s.update(order, setOf("a", "b"), null, 0)
        s.update(order, setOf("a", "b"), null, 1_000)
        // Говорит только «a»; «b» замолчал. «c» начал — ждёт, пока «b» станет вытесняемым.
        s.update(order, setOf("a", "c"), null, 2_000)
        assertEquals(listOf("a", "b"), s.update(order, setOf("a", "c"), null, 3_100), "оба наверху недавно говорили — новый ждёт")
        assertEquals(listOf("a", "c"), s.update(order, setOf("a", "c"), null, 4_100), "«b» молчит 3 с — его место у «c», «a» на своём")
    }

    @Test
    fun говорит_один_во_втором_месте_остаётся_последний() {
        val s = SpeakerSlots()
        s.update(order, setOf("c", "d"), null, 0)
        s.update(order, setOf("c", "d"), null, 1_100)
        assertEquals(listOf("c", "d"), s.update(order, setOf("c"), null, 20_000), "«d» замолчал, но его никто не вытесняет")
    }

    @Test
    fun закреплённый_держит_место_говорящие_его_не_вытесняют() {
        val s = SpeakerSlots()
        s.update(order, emptySet(), "d", 0)
        assertEquals(true, "d" in s.slots)
        s.update(order, setOf("a"), "d", 10_000)
        s.update(order, setOf("b"), "d", 20_000)
        assertEquals(true, "d" in s.update(order, setOf("b"), "d", 21_100), "закреплённый не ушёл, хотя молчит")
    }

    @Test
    fun вид_сохраняется_и_восстанавливается() {
        val v = GroupView().apply { mode = GroupMode.Speaker; perPage = 2; showSelf = false }
        val w = GroupView().apply { restore(v.saved()) }
        assertEquals(Triple(GroupMode.Speaker, 2, false), Triple(w.mode, w.perPage, w.showSelf))
        GroupView().apply { restore("мусор") }.let { assertEquals(4, it.perPage) }
    }

    @Test
    fun закреплённый_из_данных_комнаты() {
        assertEquals("u-1", RoomMeta.pinned("""{"paused":false,"pinned":"u-1"}"""))
        assertEquals("", RoomMeta.pinned("""{"paused":true,"pinned":""}"""))
        assertEquals(true, RoomMeta.paused("""{"paused": true}"""))
    }

    @Test
    fun снимок_говорящего() {
        val tiles = listOf(
            GroupTile("me", "Вы", "В", null, true, false, false, true, userId = "me"),
        ) + listOf("Анна", "Борис", "Вера", "Галина", "Дмитрий").mapIndexed { i, n ->
            GroupTile(n, n, n.take(1), null, i != 3, speaking = i == 0, paused = false, self = false, userId = n, micForbidden = i == 2)
        }
        val view = GroupView().apply { mode = GroupMode.Speaker; slots = listOf("Анна", "Борис") }
        capture("групповой-говорящий", 400, 760, dark = false) {
            CallScreen(
                state = CallState(stage = CallStage.Connected, microphoneOn = true),
                peer = "Планёрка", incoming = false, onAccept = {}, onDecline = {}, onHangUp = {},
                onMicrophone = {}, onCamera = {}, seconds = 75,
                group = GroupStage("Планёрка", tiles, 6, 25, false, true, {}, {}, view, pinnedKey = "Борис", onVoice = {}, onPin = {}),
            )
        }
        capture("групповой-вид-подокно", 400, 300, dark = false) { GroupViewChoice(view, onCollapse = {}) }
        // Не автор: пузыри в две строки, без кнопок.
        val many = tiles + listOf("Елена", "Жанна", "Зоя", "Игорь").map { GroupTile(it, it, it.take(1), null, true, false, false, false, userId = it) }
        capture("групповой-говорящий-участник", 400, 760, dark = false) {
            CallScreen(
                state = CallState(stage = CallStage.Connected, microphoneOn = true),
                peer = "Планёрка", incoming = false, onAccept = {}, onDecline = {}, onHangUp = {},
                onMicrophone = {}, onCamera = {}, seconds = 75,
                events = listOf(io.tima.core.call.CallEvent(seconds = 3, text = "Создатель запретил вам микрофон")),
                group = GroupStage("Планёрка", many, 10, 25, false, false, {}, {}, view, pinnedKey = "Борис"),
            )
        }
        capture("групповой-говорящий-вертикально", 400, 760, dark = false) {
            CallScreen(
                state = CallState(stage = CallStage.Connected, microphoneOn = true),
                peer = "Планёрка", incoming = false, onAccept = {}, onDecline = {}, onHangUp = {},
                onMicrophone = {}, onCamera = {}, seconds = 75,
                group = GroupStage("Планёрка", many, 10, 25, false, true, {}, {},
                    GroupView().apply { mode = GroupMode.Speaker; speakerVertical = true; slots = listOf("Анна", "Борис") },
                    pinnedKey = "Борис", onVoice = {}, onPin = {}),
            )
        }
        capture("групповой-говорящий-лист-событий", 400, 760, dark = false) {
            CallScreen(
                state = CallState(stage = CallStage.Connected, microphoneOn = true),
                peer = "Планёрка", incoming = false, onAccept = {}, onDecline = {}, onHangUp = {},
                onMicrophone = {}, onCamera = {}, seconds = 75,
                events = listOf(io.tima.core.call.CallEvent(seconds = 3, text = "Создатель запретил вам микрофон")),
                group = GroupStage("Планёрка", many, 10, 25, false, false, {}, {},
                    GroupView().apply { mode = GroupMode.Speaker; slots = listOf("Анна", "Борис"); eventsOpen = true }),
            )
        }
        capture("групповой-конец-не-автор", 400, 400, dark = false) {
            CallScreen(
                state = CallState(stage = CallStage.Ended),
                peer = "Планёрка", incoming = false, onAccept = {}, onDecline = {}, onHangUp = {},
                onMicrophone = {}, onCamera = {}, onClose = {}, onCallAgain = {},
                group = GroupStage("Планёрка", many, 10, 25, false, false, {}, {}, view, onJoinAgain = {}, joinLive = false),
            )
        }
    }
}
