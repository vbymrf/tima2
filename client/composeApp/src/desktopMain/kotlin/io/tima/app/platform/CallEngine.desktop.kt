package io.tima.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Desktop: готового WebRTC-клиента для JVM нет — движок остаётся заглушкой.
// Сигналинг (комната, токен, роли) уже отработал; живое медиа — на телефоне.
actual class CallEngine actual constructor() {
    private val _state = MutableStateFlow(CallMediaState.Idle)
    actual val state: StateFlow<CallMediaState> = _state
    private val _mic = MutableStateFlow(true)
    actual val micEnabled: StateFlow<Boolean> = _mic
    private val _cam = MutableStateFlow(false)
    actual val cameraEnabled: StateFlow<Boolean> = _cam
    private val _speaker = MutableStateFlow(true)
    actual val speakerOn: StateFlow<Boolean> = _speaker

    actual suspend fun connect(url: String, token: String, video: Boolean, publishMic: Boolean) {
        // no-op: на десктопе живого медиа нет
    }
    actual fun setMic(on: Boolean) { _mic.value = on }
    actual fun setCamera(on: Boolean) { _cam.value = on }
    actual fun setSpeaker(on: Boolean) { _speaker.value = on }
    actual fun disconnect() { _state.value = CallMediaState.Idle }
}

actual suspend fun ensureCallPermissions(video: Boolean): Boolean = true

@Composable
actual fun CallVideoView(engine: CallEngine, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("Видео работает на телефоне (Android)")
    }
}
