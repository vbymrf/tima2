package io.tima.shared

import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.CallPeer
import io.tima.core.call.CallSnapshot
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.CallStep
import io.tima.core.call.CallUpdate
import io.tima.core.call.Calls
import io.tima.core.call.GroupControl
import io.tima.core.call.GroupRoom
import io.tima.core.call.GroupRules
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoHandle
import io.tima.core.call.cappedTo
import io.tima.shared.CallLedger.Action
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Групповой звонок глазами телефона (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ3): лента звонков,
 * ведущий звонка, потолок по числу участников, пауза и команды создателя.
 */
class GroupCallTest {

    private val я = "u-я"
    private val создатель = "u-создатель"

    private fun групповой(state: String = "answered", from: String = создатель) =
        CallSnapshot("g1", state = state, video = true, initiatorId = from, peerId = "", groupId = "группа")

    private fun изм(cts: Long, change: String, call: CallSnapshot = групповой(), here: Boolean = false) =
        CallUpdate(cts = cts, callId = call.callId, change = change, here = here, call = call)

    // ── Лента звонков ──────────────────────────────────────────────────────────

    @Test
    fun позванному_звонит_пока_звонок_идёт_хоть_кто_то_и_вошёл() {
        // `answered` у группового значит «кто-то вошёл» — создатель входит сразу.
        assertEquals(
            listOf(Action.Ring("g1", создатель, true, "группа")),
            CallLedger.actions(я, listOf(изм(1, "ringing"))),
        )
    }

    @Test
    fun создателю_его_собственный_вызов_не_звонит() {
        assertEquals(emptyList(), CallLedger.actions(создатель, listOf(изм(1, "ringing"))))
    }

    @Test
    fun не_вошёл_за_срок_пропущенный_звонок_у_остальных_идёт() {
        val действия = CallLedger.actions(я, listOf(изм(1, "ringing"), изм(2, "missed")))
        assertEquals(
            listOf(Action.End("g1", "missed"), Action.Missed("g1", создатель, true, 0, "группа")),
            действия,
            "вызов и конец в одной пачке: не звонить, а сказать «пропущенный»",
        )
    }

    @Test
    fun вошёл_с_другого_устройства_здесь_замолкает() {
        assertEquals(
            listOf(Action.Taken("g1")),
            CallLedger.actions(я, listOf(изм(1, "answered", here = false))),
        )
    }

    @Test
    fun отказ_с_другого_устройства_гасит_вызов_без_пропущенного() {
        assertEquals(listOf(Action.End("g1", "declined")), CallLedger.actions(я, listOf(изм(1, "declined"))))
    }

    @Test
    fun кончившийся_групповой_не_звонит() {
        assertEquals(emptyList(), CallLedger.actions(я, listOf(изм(1, "ringing", групповой(state = "ended")))))
    }

    // ── Правила с сервера (решение 4) ──────────────────────────────────────────

    @Test
    fun потолок_по_числу_участников_720_480_дальше_только_голос() {
        val rules = GroupRules()
        assertEquals(720, rules.heightFor(1))
        assertEquals(720, rules.heightFor(4))
        assertEquals(480, rules.heightFor(5))
        assertEquals(480, rules.heightFor(8))
        assertNull(rules.heightFor(9), "больше 8 — только голос")
        assertEquals(8, rules.videoUpTo)
    }

    @Test
    fun набор_урезается_в_пропорции_и_кратно_16() {
        val base = io.tima.core.call.basePreset(null)
        val capped = base.cappedTo(480)
        assertEquals(480, capped.video.height)
        assertEquals(0, capped.video.width % 16)
        assertTrue(capped.video.bitrate < base.video.bitrate, "меньше точек — меньше полосы")
        assertEquals(base, base.cappedTo(base.video.height + 100), "ниже потолка набор не меняется")
    }

    // ── Ведущий звонка ─────────────────────────────────────────────────────────

    @Test
    fun начал_сам_приглашения_уходят_позванным_и_я_создатель() = runTest {
        val calls = GroupCalls(creator = я)
        val host = host(calls)
        var sent: Triple<String, String, List<String>>? = null
        host.onStarted = { g, t, who -> sent = Triple(g, t, who) }
        host.startGroup("группа", "Планёрка", ring = false, video = false, invited = listOf("u-1", "u-2"))
        assertEquals(Triple("группа", "Планёрка", listOf("u-1", "u-2")), sent)
        assertTrue(host.group?.mine == true, "создатель — я")
        assertTrue(host.isGroup)
    }

    @Test
    fun вошёл_в_чужой_звонок_приглашений_не_шлёт() = runTest {
        val host = host(GroupCalls(creator = создатель))
        var sent = false
        host.onStarted = { _, _, _ -> sent = true }
        host.joinGroup("g1", "группа", "Планёрка", video = false)
        assertFalse(sent, "приглашения шлёт только начавший")
        assertFalse(host.group?.mine == true)
    }

    @Test
    fun девятый_участник_выключает_видео_восьмерым_оно_возвращается() = runTest {
        val engine = GroupEngine()
        val host = host(GroupCalls(creator = я), engine)
        host.startGroup("группа", "Планёрка", ring = false, video = true, invited = emptyList())
        engine.say(CallState(stage = CallStage.Connected, cameraOn = true))
        engine.peersAre(8)
        assertTrue(host.voiceOnly, "девятеро вместе со мной — только голос")
        assertEquals(null, engine.ceilings.last())
        assertTrue(engine.remoteTaken.last() == false, "чужое видео не принимаем")
        val before = engine.camera.size
        host.camera(true)
        assertEquals(before, engine.camera.size, "камера при «только голос» не включается")
        assertTrue(host.events.any { it.text.contains("только голос") }, "сказано словами, почему")
        engine.peersAre(4)
        assertFalse(host.voiceOnly)
        assertEquals(480, engine.ceilings.last(), "пятеро — 480")
        assertTrue(engine.remoteTaken.last() == true)
    }

    @Test
    fun пауза_создателя_гасит_своё_и_возвращает_как_было() = runTest {
        val engine = GroupEngine()
        val host = host(GroupCalls(creator = создатель), engine)
        host.joinGroup("g1", "группа", "Планёрка", video = false)
        engine.say(CallState(stage = CallStage.Connected, microphoneOn = true, cameraOn = false))
        engine.say(CallState(stage = CallStage.Connected, microphoneOn = true, cameraOn = false, roomPaused = true))
        assertEquals(false, engine.mic.last(), "на паузе микрофон стоит")
        engine.say(CallState(stage = CallStage.Connected, microphoneOn = false, cameraOn = false, roomPaused = false))
        assertEquals(true, engine.mic.last(), "после паузы — как было")
    }

    @Test
    fun удалили_из_звонка_звонок_кончился_для_меня() = runTest {
        val engine = GroupEngine()
        val host = host(GroupCalls(creator = создатель), engine)
        host.joinGroup("g1", "группа", "Планёрка", video = false)
        engine.say(CallState(stage = CallStage.Connected))
        host.controlled("g1", GroupControl.Remove.wire, создатель)
        assertEquals(CallStage.Ended, host.state.stage)
        assertTrue(host.events.any { it.text.contains("удалил") })
    }

    @Test
    fun запрет_создателя_не_даёт_включить_пока_не_разрешат() = runTest {
        val engine = GroupEngine()
        val host = host(GroupCalls(creator = создатель), engine)
        host.joinGroup("g1", "группа", "Планёрка", video = false)
        engine.say(CallState(stage = CallStage.Connected, microphoneOn = true))
        host.controlled("g1", GroupControl.MuteMic.wire, создатель)
        assertTrue(host.micForbidden)
        assertEquals(false, engine.mic.last(), "микрофон выключен сразу")
        val before = engine.mic.size
        host.microphone(true)
        assertEquals(before, engine.mic.size, "запрещённый микрофон кнопкой не включается")
        host.controlled("g1", GroupControl.MuteVideo.wire, создатель)
        assertTrue(host.events.any { it.text.contains("смотрите и слушаете") }, "оба запрета — человек только смотрит")
        host.controlled("g1", GroupControl.AllowMic.wire, создатель)
        assertFalse(host.micForbidden)
        assertFalse(host.events.any { it.text.contains("смотрите и слушаете") }, "снят один запрет — уже не только зритель")
        host.microphone(true)
        assertEquals(true, engine.mic.last(), "разрешённый включается")
    }

    @Test
    fun уход_одного_участника_группового_не_кладёт_трубку() = runTest {
        val host = host(GroupCalls(creator = создатель))
        host.joinGroup("g1", "группа", "Планёрка", video = false)
        assertFalse(host.peerIs(создатель), "в групповом чужой уход разговора не кончает")
    }

    @Test
    fun пропущенный_групповой_вызов_не_говорит_серверу_отказ() = runTest {
        val calls = GroupCalls(creator = создатель)
        val host = host(calls)
        host.ringGroup("g1", "группа", "Планёрка", создатель, video = false)
        advanceTimeBy(46_000)
        assertEquals(CallStage.Ended, host.state.stage)
        assertTrue(calls.ended.isEmpty(), "`/end` сделал бы из пропущенного «отклонил»")
    }

    @Test
    fun команды_только_у_создателя() = runTest {
        val calls = GroupCalls(creator = создатель)
        val host = host(calls)
        host.joinGroup("g1", "группа", "Планёрка", video = false)
        host.control(GroupControl.MuteMic, "u-2")
        assertTrue(calls.controls.isEmpty(), "не создатель командовать не может")
    }

    // ── Подставные ─────────────────────────────────────────────────────────────

    private fun TestScope.host(calls: Calls, engine: GroupEngine = GroupEngine()) = CallHost(
        calls,
        engine,
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        words = { io.tima.core.words.RussianWords },
        access = { _, done -> done(true) },
        serviceOn = {},
        serviceOff = {},
    ).also { it.myUserId = { я } }

    private class GroupCalls(private val creator: String) : Calls {
        val ended = mutableListOf<String>()
        val controls = mutableListOf<Pair<GroupControl, String>>()
        private fun door(callId: String) = CallStep.Door(
            CallDoor(
                callId = callId, room = "комната", url = "wss://х", token = "жетон",
                group = GroupRoom(groupId = "группа", creatorId = creator),
            ),
        )
        override suspend fun start(peerId: String, video: Boolean): CallStep = CallStep.Refused("не здесь")
        override suspend fun answer(callId: String): CallStep = CallStep.Refused("не здесь")
        override suspend fun join(callId: String): CallStep = door(callId)
        override suspend fun startGroup(groupId: String, ring: Boolean, video: Boolean, invited: List<String>) = door("g1")
        override suspend fun control(callId: String, action: GroupControl, userId: String): Boolean {
            controls += action to userId
            return true
        }
        override suspend fun end(callId: String, busy: Boolean): Boolean {
            ended += callId
            return true
        }
    }

    private class GroupEngine : CallEngine {
        private val _state = MutableStateFlow(CallState())
        override val state: StateFlow<CallState> = _state.asStateFlow()
        private val none = MutableStateFlow<VideoHandle?>(null)
        override val localVideo: StateFlow<VideoHandle?> = none.asStateFlow()
        override val remoteVideo: StateFlow<VideoHandle?> = none.asStateFlow()
        private val _peers = MutableStateFlow<List<CallPeer>>(emptyList())
        override val peers: StateFlow<List<CallPeer>> = _peers.asStateFlow()
        val camera = mutableListOf<Boolean>()
        val mic = mutableListOf<Boolean>()
        val ceilings = mutableListOf<Int?>()
        val remoteTaken = mutableListOf<Boolean>()
        override suspend fun connect(door: CallDoor, publish: PublishPreset?) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun setMicrophone(on: Boolean) {
            mic += on
        }
        override suspend fun setCamera(on: Boolean) {
            camera += on
        }
        override suspend fun setVideoCeiling(height: Int?) {
            ceilings += height
        }
        override suspend fun setRemoteVideo(on: Boolean) {
            remoteTaken += on
        }
        fun say(state: CallState) {
            _state.value = state
        }
        fun peersAre(count: Int) {
            _peers.value = (1..count).map { CallPeer(identity = "u-$it:d", userId = "u-$it") }
        }
    }
}
