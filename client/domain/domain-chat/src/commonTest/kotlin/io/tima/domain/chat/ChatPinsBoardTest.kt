package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Закреплённые группой на устройстве (ПЛАН-(СЦ) СЦ12): снимок из списка, из прежнего события, старый сервер. */
class ChatPinsBoardTest {

    private class OldServer(var one: Long) : RemotePins {
        val added = mutableListOf<Long>()
        override suspend fun read(chatId: String, group: Boolean): Long = one
        override suspend fun set(chatId: String, group: Boolean, serverId: Long): PinStep { added += serverId; one = serverId; return PinStep.Done }
        override suspend fun clear(chatId: String, group: Boolean): PinStep { one = 0; return PinStep.Done }
    }

    @Test
    fun список_из_события_и_новейшее_сверху() {
        val pins = ChatPins(OldServer(0))
        pins.applied("g", PinBoard("Важное", listOf(9, 4)))
        assertEquals(9L, pins.pinned.value["g"], "прежнее «одно» — новейшее из списка")
        assertEquals(PinBoard("Важное", listOf(9, 4)), pins.boards.value["g"])
        pins.deleted("g", 4)
        assertEquals(listOf(9L), pins.boards.value["g"]?.ids, "удалённое у всех уходит и из списка")
    }

    @Test
    fun прежнее_событие_добавляет_сверху_и_ноль_снимает_всё() {
        val pins = ChatPins(OldServer(0))
        pins.applied("c", 5)
        pins.applied("c", 8)
        assertEquals(listOf(8L, 5L), pins.boards.value["c"]?.ids)
        pins.applied("c", 0)
        assertEquals(emptyList(), pins.boards.value["c"]?.ids)
    }

    @Test
    fun старый_сервер_список_из_одного_и_закрепить_через_прежнюю_ручку() = runTest {
        val old = OldServer(3)
        val pins = ChatPins(old)
        pins.refresh("c", group = false)
        assertEquals(PinBoard("", listOf(3)), pins.boards.value["c"])
        assertEquals(PinStep.Done, pins.pin("c", group = false, serverId = 6))
        assertEquals(listOf(6L), old.added, "добавление на старом сервере — прежний «…/pin»")
    }
}
