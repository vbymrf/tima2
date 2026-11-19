package io.tima.shared

import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.GroupRoom
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.VoiceChatInfo
import io.tima.core.network.VoiceChatsOverHttp
import io.tima.core.network.VoiceEnterStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Звуковой чат, в котором человек сейчас (ПЛАН-(СЦ) СЦ7). Свой экземпляр движка — не тот, что у звонков: за
 * движком звонков следит `CallHost`, и вход в звуковой чат через него оживил бы окно звонка.
 *
 * Говорят все, кто вошёл (§1.5, 2). Звонок во время чата (§1.5, 4): чат на паузе — движок отключается, после
 * звонка — входит снова тем же номером.
 */
class VoiceSession(
    private val api: VoiceChatsOverHttp,
    private val engine: CallEngine,
    private val scope: CoroutineScope,
) {
    data class State(
        val chat: VoiceChatInfo? = null,
        val title: String = "",
        val inside: Boolean = false,
        val mic: Boolean = true,
        val sound: Boolean = true,
        val paused: Boolean = false,
        val trouble: VoiceEnterStep? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Кто говорит — участники комнаты движка. */
    val peers get() = engine.peers

    fun enter(roomId: String, title: String) {
        scope.launch {
            val step = api.enter(roomId)
            Journal.note(LogCode.CALL, "звуковой чат: вход", "итог" to (step::class.simpleName ?: "?"))
            if (step !is VoiceEnterStep.Entered) {
                _state.value = _state.value.copy(trouble = step)
                return@launch
            }
            val e = step.entry
            runCatching {
                engine.connect(CallDoor(callId = "voice-${e.chat.roomId}", room = e.room, url = e.url, token = e.token,
                    group = GroupRoom(groupId = e.chat.groupId.ifEmpty { e.chat.roomId }, creatorId = "")))
                engine.setMicrophone(true)
                engine.setCamera(false)
            }.onFailure { Journal.trouble(LogCode.CALL, "звуковой чат: движок", "почему" to (it.message ?: "?")) }
            _state.value = State(chat = e.chat, title = title, inside = true)
        }
    }

    fun leave() {
        val chat = _state.value.chat ?: return
        _state.value = State()
        scope.launch {
            runCatching { engine.disconnect() }
            api.leave(chat.roomId)
            Journal.note(LogCode.CALL, "звуковой чат: вышел")
        }
    }

    fun mic(on: Boolean) {
        if (_state.value.chat?.allowMute == false) return
        _state.value = _state.value.copy(mic = on)
        scope.launch { runCatching { engine.setMicrophone(on) } }
    }

    fun sound(on: Boolean) {
        if (_state.value.chat?.allowMute == false) return
        _state.value = _state.value.copy(sound = on)
        scope.launch { runCatching { engine.setRemoteAudioSilent(!on) } }
    }

    /** Свежий состав и ведущий — с сервера. */
    fun refresh() {
        val chat = _state.value.chat ?: return
        scope.launch {
            api.list(chat.communityId)?.firstOrNull { it.roomId == chat.roomId }?.let { _state.value = _state.value.copy(chat = it) }
        }
    }

    fun host(userId: String) {
        val chat = _state.value.chat ?: return
        scope.launch { api.host(chat.roomId, userId); refresh() }
    }

    fun kick(userId: String) {
        val chat = _state.value.chat ?: return
        scope.launch { api.kick(chat.roomId, userId); refresh() }
    }

    /** Звонок начался — чат на паузе; кончился — вход снова (§1.5, 4). */
    fun onCall(active: Boolean) {
        val s = _state.value
        if (!s.inside) return
        if (active && !s.paused) {
            _state.value = s.copy(paused = true)
            scope.launch { runCatching { engine.disconnect() } }
        } else if (!active && s.paused) {
            val chat = s.chat ?: return
            _state.value = s.copy(paused = false)
            enter(chat.roomId, s.title)
        }
    }
}

/** Полоса звукового чата над окнами (§1.8, 4): ставит `App`, рисует корень над всем — как полосу звонка. */
internal object VoiceStripSlot {
    var current by androidx.compose.runtime.mutableStateOf<(@androidx.compose.runtime.Composable () -> Unit)?>(null)
}
