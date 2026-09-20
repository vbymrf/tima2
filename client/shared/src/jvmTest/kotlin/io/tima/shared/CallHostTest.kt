package io.tima.shared

import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.CallStep
import io.tima.core.call.Calls
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Состояние звонка: что считается «занят».
 *
 * ── ЗАЧЕМ ЭТИ ПРОВЕРКИ ──────────────────────────────────────────────────────
 *
 * Здесь живёт разница между «окно звонка показано» и «идёт разговор», и подмена одного
 * другим стоила живой проверки 2026-09-20: пока человек не нажал «Закрыть» на
 * завершённом звонке, **входящие к нему не доходили вовсе**. Со стороны звонящего это
 * выглядело как «Звоним…» без конца, со стороны принимающего — как тишина, а «чинилось»
 * погасшим экраном: Android пересоздавал окно, и состояние сбрасывалось само.
 *
 * Снимками такое не поймать — на экране всё правильно. Ловится только здесь.
 */
class CallHostTest {

    @Test
    fun завершённый_звонок_не_мешает_новому_входящему() = runTest {
        val host = host()

        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)
        assertTrue(host.active, "входящий не показан")
        host.hangUp()
        assertEquals(CallStage.Ended, host.state.stage)
        assertTrue(host.active, "окно завершённого звонка обязано остаться: там «Перезвонить» и «Закрыть»")

        // И вот главное: окно на экране, а мы свободны.
        host.ring(callId = "второй", fromId = "u-2", fromName = "Борис", video = false)
        assertEquals("Борис", host.peer, "второй входящий проглочен завершённым звонком")
        assertEquals(CallStage.Connecting, host.state.stage)
    }

    @Test
    fun завершённый_звонок_не_мешает_позвонить_самому() = runTest {
        val host = host()

        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)
        host.hangUp()

        host.start(peerId = "u-9", peerName = "Вера", video = false)
        assertEquals("Вера", host.peer, "новый исходящий не начался поверх завершённого")
        assertFalse(host.incoming, "новый звонок помечен входящим")
    }

    @Test
    fun во_время_разговора_второй_звонок_не_начинается() = runTest {
        // Обратная сторона: запрет обязан работать, пока разговор идёт. Ради этого он и
        // заводился — три нажатия «позвонить» однажды завели три звонка на сервере.
        val host = host()

        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)
        host.start(peerId = "u-9", peerName = "Вера", video = false)
        assertEquals("Аня", host.peer, "второй звонок начался поверх идущего")
    }

    @Test
    fun трубка_кладётся_один_раз() = runTest {
        // В журнале это видно парами: два «звонок закончен» с одним идентификатором и два
        // POST /end подряд. Сервер такое переживает, а отчёт становится вдвое длиннее и
        // вдвое менее понятным — по нему кажется, что звонков было два.
        val calls = FakeCalls()
        val host = host(calls)

        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)
        host.hangUp()
        host.hangUp()
        host.hangUp()

        assertEquals(1, calls.ended.size, "трубку положили несколько раз: ${calls.ended}")
    }

    /**
     * `backgroundScope`, а не сам тест.
     *
     * `CallHost` в конструкторе подписывается на состояние движка, и подписка не кончается
     * никогда — она и не должна. Запущенная в области самого теста, она не давала бы ему
     * завершиться: `runTest` ждёт всех своих детей. Для такого у него и заведён
     * `backgroundScope` — он гасится, когда тест закончился.
     */
    private fun TestScope.host(calls: Calls = FakeCalls()) = CallHost(
        calls,
        FakeEngine(),
        // **Неограниченный диспетчер, а не очередь.** `CallHost` делает работу в
        // корутинах — кладёт трубку, зовёт сигналинг, — и с очередью её пришлось бы
        // «проматывать» вручную в каждой проверке. Здесь она случается сразу, и проверка
        // читается как рассказ: позвонили, положили, положили ещё раз.
        //
        // `backgroundScope` — потому что подписка на состояние движка не кончается
        // никогда, и в области самого теста она не дала бы ему завершиться.
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        words = { io.tima.core.words.RussianWords },
    )

    /** Сигналинг, который всегда открывает дверь и помнит, что закрывал. */
    private class FakeCalls : Calls {
        val ended = mutableListOf<String>()
        override suspend fun start(peerId: String, video: Boolean): CallStep =
            CallStep.Door(CallDoor(callId = "дверь", room = "комната", url = "wss://х", token = "жетон"))

        override suspend fun answer(callId: String): CallStep =
            CallStep.Door(CallDoor(callId = callId, room = "комната", url = "wss://х", token = "жетон"))

        override suspend fun end(callId: String): Boolean {
            ended += callId
            return true
        }
    }

    /** Движок, который ничего не делает, но существует: без него звонки «невозможны». */
    private class FakeEngine : CallEngine {
        private val _state = MutableStateFlow(CallState())
        override val state: StateFlow<CallState> = _state.asStateFlow()
        private val none = MutableStateFlow<VideoHandle?>(null)
        override val localVideo: StateFlow<VideoHandle?> = none.asStateFlow()
        override val remoteVideo: StateFlow<VideoHandle?> = none.asStateFlow()
        override suspend fun connect(door: CallDoor, publish: PublishPreset?) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun setMicrophone(on: Boolean) = Unit
        override suspend fun setCamera(on: Boolean) = Unit
    }
}
