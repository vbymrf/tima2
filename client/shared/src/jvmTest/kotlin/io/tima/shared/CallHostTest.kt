package io.tima.shared

import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.CallStep
import io.tima.core.call.Calls
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoHandle
import kotlinx.coroutines.CompletableDeferred
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
            access = allowed,
        )

        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)
        engine.say(CallState(stage = CallStage.Connected))

        advanceTimeBy(90_000)

        assertEquals(CallStage.Connected, host.state.stage, "сторож оборвал идущий разговор")
        assertTrue(calls.ended.isEmpty(), "сторож положил трубку в разговоре: ${calls.ended}")
    }

    @Test
    fun занятость_приходит_словом_сервера_а_не_отказом() = runTest {
        // Слово «занят» знает телефон собеседника, а не база. Проверка по состоянию в
        // базе была и однажды заперла всех на пять часов: незакрытая строка делала
        // человека занятым, пока не истечёт окно (2026-09-20).
        val host = host()
        host.start(peerId = "u-9", peerName = "Вера", video = false)
        // Сервер закрыл звонок словом busy — так сказал телефон Веры.
        host.ended("busy")

        assertEquals(CallStage.Ended, host.state.stage, "звонок занятому не кончился")
        assertEquals(
            io.tima.core.words.RussianWords.call.peerBusy,
            host.events.lastOrNull()?.text,
            "про занятость не сказано словами: ${host.events.map { it.text }}",
        )
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
            access = allowed,
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
            access = allowed,
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
            access = allowed,
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

    @Test
    fun незабранный_вызов_говорится_словами_и_не_кладёт_трубку() = runTest {
        // Сервер сказал: ни одно устройство собеседника вызов не подтвердило. Это слово о
        // связи, а не о человеке (ADR-0025 §1а): устройство вернётся — возьмёт вызов из
        // журнала само. Положить трубку здесь значило бы решить за него, что оно
        // опоздало, а сорок пять секунд ещё идут.
        val calls = FakeCalls()
        val engine = FakeEngine()
        val host = CallHost(
            calls,
            engine,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            words = { io.tima.core.words.RussianWords },
            access = allowed,
        )

        host.start(peerId = "u-9", peerName = "Вера", video = false)
        host.peerOffline("дверь")

        assertEquals(CallStage.Connecting, host.state.stage, "слово о связи положило трубку")
        assertTrue(calls.ended.isEmpty(), "серверу сказали, что звонок кончен: ${calls.ended}")
        assertTrue(
            host.events.any { it.text == io.tima.core.words.RussianWords.call.peerOffline },
            "про отсутствие связи не сказано словами: ${host.events.map { it.text }}",
        )

        // Устройство нашлось и ответило — строка стала неправдой и снимается.
        engine.say(CallState(stage = CallStage.Connected))
        assertTrue(
            host.events.none { it.text == io.tima.core.words.RussianWords.call.peerOffline },
            "строка осталась после ответа: ${host.events.map { it.text }}",
        )
    }

    @Test
    fun слово_о_чужом_звонке_не_принимается() = runTest {
        // У человека может идти один разговор и висеть незабранный вызов по другому.
        // Раньше на этом месте — на кадрах call.state — клалась трубка живого звонка.
        val host = host()
        host.start(peerId = "u-9", peerName = "Вера", video = false)

        host.peerOffline("чужая дверь")

        assertTrue(
            host.events.none { it.text == io.tima.core.words.RussianWords.call.peerOffline },
            "приняли слово о чужом звонке: ${host.events.map { it.text }}",
        )
    }

    @Test
    fun перезвонить_звонит_тем_видом_который_стал_камерой() = runTest {
        // Переключатель у «Перезвонить» — переменная окна 0 (заказчик 2026-09-25): вид
        // ставит то, как окно открыли, а дальше его ведёт камера.
        val calls = FakeCalls()
        val host = host(calls)

        host.start(peerId = "u-9", peerName = "Вера", video = false)
        assertFalse(host.video, "голосовой звонок открыл окно видео")
        host.camera(true)
        assertTrue(host.video, "камеру включили — звонок не стал видео")
        host.hangUp()

        host.again()
        assertEquals(listOf(false, true), calls.startedVideo, "перезвонили не тем видом")

        host.camera(false)
        assertFalse(host.video, "камеру выключили — звонок не стал голосовым")
    }

    @Test
    fun переключатель_решает_вид_повтора() = runTest {
        val calls = FakeCalls()
        val host = host(calls)

        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = true)
        assertTrue(host.video, "окно входящего видеовызова открылось голосом")
        host.hangUp()

        host.redialAs(video = false)
        host.again()
        assertEquals(listOf(false), calls.startedVideo, "переключатель не решил вид повтора")
    }

    @Test
    fun трубку_положили_пока_входили_в_комнату_камера_и_служба_не_поднимаются() = runTest {
        // Redmi 2026-09-30: звонок положен через 0,1 с, пока шёл вход в комнату; код после
        // `connect` поднял службу у законченного звонка — Android закрыл приложение.
        val engine = FakeEngine()
        val gate = CompletableDeferred<Unit>()
        engine.onConnect = { gate.await() }
        val host = host(engine = engine)

        host.start("u-1", "Аня", video = true)
        host.hangUp()
        gate.complete(Unit)

        assertEquals(emptyList(), engine.camera, "камера включилась у законченного звонка")
    }

    @Test
    fun вошли_в_комнату_камера_видеозвонка_включается() = runTest {
        val engine = FakeEngine()
        val host = host(engine = engine)

        host.start("u-1", "Аня", video = true)

        assertEquals(listOf(true), engine.camera)
    }

    // ── СВЕРНУЛИ ПОСРЕДИ ВИДЕОЗВОНКА (заказчик 2026-10-01, 1б и 1в) ──────────

    /** Видеозвонок идёт, своя камера включена. */
    private fun TestScope.videoCall(engine: FakeEngine, keep: Boolean = false): CallHost {
        val host = host(engine = engine, cameraInBackground = { keep })
        host.start("u-1", "Аня", video = true)
        engine.camera.clear()
        engine.say(CallState(stage = CallStage.Connected, cameraOn = true))
        return host
    }

    @Test
    fun свернули_дольше_двух_секунд_своё_видео_на_паузу_вернулись_идёт() = runTest {
        val engine = FakeEngine()
        val host = videoCall(engine)

        host.appVisible(false)
        advanceTimeBy(2_100)
        assertEquals(listOf(false), engine.camera, "видео не встало на паузу")
        assertEquals(listOf(true), engine.paused, "собеседнику не сказали про паузу")

        host.appVisible(true)
        assertEquals(listOf(false, true), engine.camera, "камера не включилась после возврата")
        assertEquals(listOf(true, false), engine.paused)
    }

    @Test
    fun взгляд_в_шторку_короче_двух_секунд_паузы_не_ставит() = runTest {
        val engine = FakeEngine()
        val host = videoCall(engine)

        host.appVisible(false)
        advanceTimeBy(1_000)
        host.appVisible(true)
        advanceTimeBy(5_000)

        assertEquals(emptyList(), engine.camera)
        assertEquals(emptyList(), engine.paused)
    }

    @Test
    fun продолжать_показывать_паузы_нет() = runTest {
        val engine = FakeEngine()
        val host = videoCall(engine, keep = true)

        host.appVisible(false)
        advanceTimeBy(10_000)

        assertEquals(emptyList(), engine.camera, "при «продолжать показывать» камеру выключили")
    }

    @Test
    fun вернулись_а_кадров_нет_камера_открывается_заново() = runTest {
        val engine = FakeEngine()
        val host = videoCall(engine, keep = true)
        engine.frames = 100 // кадры стоят — камеру отобрали в фоне

        host.appVisible(false)
        host.appVisible(true)
        advanceTimeBy(4_000)

        assertEquals(1, engine.restarts, "камеру без кадров не открыли заново")
    }

    @Test
    fun вернулись_и_кадры_идут_камеру_не_трогаем() = runTest {
        val engine = FakeEngine()
        val host = videoCall(engine, keep = true)
        engine.framesGrow = true

        host.appVisible(false)
        host.appVisible(true)
        advanceTimeBy(4_000)

        assertEquals(0, engine.restarts)
    }

    @Test
    fun гудок_вызова_пока_дозваниваемся_и_занято_в_конце() = runTest {
        // Заказчик 2026-10-08: пока дозваниваемся — гудок, занято — короткие несколько секунд.
        val ring = mutableListOf<Boolean>()
        var busy = 0
        val engine = FakeEngine()
        val host = CallHost(
            FakeCalls(),
            engine,
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            words = { io.tima.core.words.RussianWords },
            access = allowed,
            ringback = { ring += it },
            busyTone = { busy++ },
        )
        fun settle() = androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()

        host.start(peerId = "u-9", peerName = "Вера", video = false)
        settle()
        assertEquals(true, ring.lastOrNull(), "гудок вызова не пошёл: $ring")
        engine.say(CallState(stage = CallStage.Connected))
        settle()
        assertEquals(false, ring.lastOrNull(), "ответили, а гудок идёт: $ring")
        assertEquals(0, busy, "«занято» в отвеченном звонке")

        host.close()
        host.start(peerId = "u-9", peerName = "Вера", video = false)
        host.ended("busy")
        settle()
        assertEquals(false, ring.lastOrNull(), "занято, а гудок вызова идёт: $ring")
        assertEquals(1, busy, "«занято» не прозвучало")
    }

    @Test
    fun входящий_гудком_вызова_не_звучит() = runTest {
        val ring = mutableListOf<Boolean>()
        val host = CallHost(
            FakeCalls(),
            FakeEngine(),
            CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            words = { io.tima.core.words.RussianWords },
            access = allowed,
            ringback = { ring += it },
        )
        host.ring(callId = "первый", fromId = "u-1", fromName = "Аня", video = false)
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        assertTrue(ring.none { it }, "у входящего звучит гудок звонящего: $ring")
    }

    private fun TestScope.host(
        calls: Calls = FakeCalls(),
        engine: FakeEngine = FakeEngine(),
        cameraInBackground: () -> Boolean = { false },
    ) = CallHost(
        calls,
        engine,
        // **Неограниченный диспетчер, а не очередь.** `CallHost` делает работу в
        // корутинах — кладёт трубку, зовёт сигналинг, — и с очередью её пришлось бы
        // «проматывать» вручную в каждой проверке. Здесь она случается сразу, и проверка
        // читается как рассказ: позвонили, положили, положили ещё раз.
        //
        // `backgroundScope` — потому что подписка на состояние движка не кончается
        // никогда, и в области самого теста она не дала бы ему завершиться.
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        words = { io.tima.core.words.RussianWords },
        access = allowed,
        cameraInBackground = cameraInBackground,
    )

    /**
     * Доступ к микрофону и камере — **всегда дан**. Проверки идут на JVM, а там вопрос
     * задаётся Windows (ПК3): на машине с запретом «Конфиденциальности» они падали бы от
     * настроек машины, а не от кода.
     */
    private val allowed: (Boolean, (Boolean) -> Unit) -> Unit = { _, done -> done(true) }

    /** Сигналинг, который всегда отказывает одним и тем же кодом. */
    private class RefusingCalls(private val code: String) : Calls {
        override suspend fun start(peerId: String, video: Boolean): CallStep = CallStep.Refused(code)
        override suspend fun answer(callId: String): CallStep = CallStep.Refused(code)
        override suspend fun end(callId: String, busy: Boolean): Boolean = true
    }

    /** Сигналинг, который всегда открывает дверь и помнит, что закрывал. */
    private class FakeCalls : Calls {
        val ended = mutableListOf<String>()
        /** Каким видом звонили: `true` — видео. По одному на каждый исходящий. */
        val startedVideo = mutableListOf<Boolean>()
        override suspend fun start(peerId: String, video: Boolean): CallStep {
            startedVideo += video
            return CallStep.Door(CallDoor(callId = "дверь", room = "комната", url = "wss://х", token = "жетон"))
        }

        override suspend fun answer(callId: String): CallStep =
            CallStep.Door(CallDoor(callId = callId, room = "комната", url = "wss://х", token = "жетон"))

        override suspend fun end(callId: String, busy: Boolean): Boolean {
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
        /** Что делает вход в комнату — по умолчанию ничего, сразу. */
        var onConnect: suspend () -> Unit = {}
        /** Как включали и выключали камеру. */
        val camera = mutableListOf<Boolean>()
        override suspend fun connect(door: CallDoor, publish: PublishPreset?) = onConnect()
        override suspend fun disconnect() = Unit
        override suspend fun setMicrophone(on: Boolean) = Unit
        override suspend fun setCamera(on: Boolean) {
            camera += on
        }

        /** Что сказали собеседнику про паузу видео. */
        val paused = mutableListOf<Boolean>()
        var frames = 0L
        var framesGrow = false
        var restarts = 0
        override suspend fun announcePaused(paused: Boolean) {
            this.paused += paused
        }

        override suspend fun cameraFrames(): Long? {
            if (framesGrow) frames += 30
            return frames
        }

        override suspend fun restartCamera() {
            restarts++
        }

        /** Сказать за движок: «комната ответила вот этим». */
        fun say(state: CallState) {
            _state.value = state
        }
    }
}
