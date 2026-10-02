package io.tima.shared

import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoHandle
import io.tima.domain.chat.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** «Отключить динамик» (заказчик 2026-10-02): звук с сервера молчит только во время прогона. */
@OptIn(ExperimentalCoroutinesApi::class)
class BenchSpeakerOffTest {

    private class Memory : Settings {
        val values = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun all(): Flow<Map<String, String>> = values
        override suspend fun put(name: String, value: String) {
            values.value = values.value + (name to value)
        }
    }

    private class Engine : CallEngine {
        private val _state = MutableStateFlow(CallState())
        override val state: StateFlow<CallState> = _state.asStateFlow()
        private val none = MutableStateFlow<VideoHandle?>(null)
        override val localVideo: StateFlow<VideoHandle?> = none.asStateFlow()
        override val remoteVideo: StateFlow<VideoHandle?> = none.asStateFlow()
        val silent = mutableListOf<Boolean>()
        override suspend fun connect(door: CallDoor, publish: PublishPreset?) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun setMicrophone(on: Boolean) = Unit
        override suspend fun setCamera(on: Boolean) = Unit
        override suspend fun setRemoteAudioSilent(silent: Boolean) {
            this.silent += silent
        }
        fun say(stage: CallStage) {
            _state.value = CallState(stage = stage)
        }
    }

    @Test
    fun динамик_молчит_только_в_прогоне_во_время_разговора() = runTest {
        val settings = Memory()
        val engine = Engine()
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        settings.put(BenchStore.KEY_FLAG, "yes")
        settings.put(BenchStore.KEY_SPEAKER_OFF, "yes")
        val bench = BenchStore(settings, engine, scope)

        engine.say(CallStage.Connected)
        assertEquals(listOf(false), engine.silent, "без «Начать прогон» звук играет")

        bench.arm(true)
        assertEquals(listOf(false, true), engine.silent, "прогон и разговор — молчит")

        engine.say(CallStage.Ended)
        assertEquals(listOf(false, true, false), engine.silent, "разговор кончился — вернули")

        bench.speakerOff(false)
        engine.say(CallStage.Connected)
        assertEquals(listOf(false, true, false), engine.silent, "галочка снята — не глушим")
    }
}
