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
import kotlinx.coroutines.test.advanceTimeBy
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
    @Test
    fun звонок_без_ответа_кончается_сам() = runTest {
        // Сервер таймаута не ставит, и до 2026-09-20 «Звоним…» шло вечно: собеседник мог
        // просто не взять телефон в руки. Для звонящего это выглядело поломкой — телефон
        // делает вид, что дозванивается, а дозваниваться уже не к кому.
        val calls = FakeCalls()
        val host = host(calls)

        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)
        assertEquals(CallStage.Connecting, host.state.stage)

        advanceTimeBy(46_000)

        assertEquals(CallStage.Ended, host.state.stage, "звонок без ответа не кончился сам")
        assertEquals(listOf("первый"), calls.ended, "серверу не сказали, что не дозвонились")
    }

    @Test
    fun отвеченный_звонок_сторож_не_трогает() = runTest {
        // Обратная сторона: сторож обязан сниматься, как только ответили. Иначе разговор
        // обрывался бы на сорок пятой секунде — ровно тогда, когда он уже идёт.
        val engine = FakeEngine()
        val calls = FakeCalls()
        val host = CallHost(
            calls,
            engine,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            words = { io.tima.core.words.RussianWords },
        )

        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)
        engine.say(CallState(stage = CallStage.Connected))

        advanceTimeBy(90_000)

        assertEquals(CallStage.Connected, host.state.stage, "сторож оборвал идущий разговор")
        assertTrue(calls.ended.isEmpty(), "сторож положил трубку в разговоре: ${calls.ended}")
    }

    @Test
    fun занятый_собеседник_назван_своим_словом() = runTest {
        // «Занят» и «не ответил» человек различает и поступает по-разному: не ответившему
        // перезванивают сразу, занятого ждут. Раньше сервер заводил звонок занятому как
        // обычный, тот его не видел (один сеанс за раз), а звонящий слушал гудки до
        // своего срока и узнавал неправду — «никто не ответил».
        val host = CallHost(
            RefusingCalls("busy"),
            FakeEngine(),
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            words = { io.tima.core.words.RussianWords },
        )

        host.start(peerId = "u-9", peerName = "Вера", video = false)

        assertEquals(CallStage.Ended, host.state.stage, "звонок занятому не кончился сразу")
        assertEquals(
            io.tima.core.words.RussianWords.call.peerBusy,
            host.events.lastOrNull()?.text,
            "про занятость не сказано словами: ${host.events.map { it.text }}",
        )
    }

    @Test
    fun длящееся_событие_снимается_когда_кончилось() = runTest {
        // «Собеседник показывает себя, ваша камера выключена» — правда ровно до того
        // мгновения, когда камеру включили. Строка, висящая после, утверждает неверное
        // (заказчик 2026-09-20).
        val engine = FakeEngine()
        val host = CallHost(
            FakeCalls(),
            engine,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            words = { io.tima.core.words.RussianWords },
        )
        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)

        engine.say(CallState(stage = CallStage.Connected, remoteVideoShown = true, cameraOn = false))
        assertTrue(
            host.events.any { it.text == io.tima.core.words.RussianWords.call.peerShowsSelf },
            "не сказано, что собеседник показывает себя при нашей выключенной камере",
        )

        engine.say(CallState(stage = CallStage.Connected, remoteVideoShown = true, cameraOn = true))
        assertTrue(
            host.events.none { it.text == io.tima.core.words.RussianWords.call.peerShowsSelf },
            "строка осталась после включения камеры: ${host.events.map { it.text }}",
        )
    }

    @Test
    fun длящееся_событие_не_повторяется() = runTest {
        // Состояние обновляется десятки раз за звонок. Без защиты лента состояла бы из
        // одной строки, повторённой сорок раз, и листать её было бы незачем.
        val engine = FakeEngine()
        val host = CallHost(
            FakeCalls(),
            engine,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            words = { io.tima.core.words.RussianWords },
        )
        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)

        repeat(5) { at ->
            engine.say(CallState(stage = CallStage.Connected, remoteVideoShown = true, others = listOf("u-$at")))
        }

        assertEquals(
            1,
            host.events.count { it.text == io.tima.core.words.RussianWords.call.peerShowsSelf },
            "событие повторилось: ${host.events.map { it.text }}",
        )
    }

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

    /** Сигналинг, который всегда отказывает одним и тем же кодом. */
    private class RefusingCalls(private val code: String) : Calls {
        override suspend fun start(peerId: String, video: Boolean): CallStep = CallStep.Refused(code)
        override suspend fun answer(callId: String): CallStep = CallStep.Refused(code)
        override suspend fun end(callId: String): Boolean = true
    }

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

        /** Сказать за движок: «комната ответила вот этим». */
        fun say(state: CallState) {
            _state.value = state
        }
    }
}
