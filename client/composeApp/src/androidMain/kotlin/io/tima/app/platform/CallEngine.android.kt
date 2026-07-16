package io.tima.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

actual class CallEngine actual constructor() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var room: Room? = null

    private val _state = MutableStateFlow(CallMediaState.Idle)
    actual val state: StateFlow<CallMediaState> = _state
    private val _mic = MutableStateFlow(true)
    actual val micEnabled: StateFlow<Boolean> = _mic
    private val _cam = MutableStateFlow(false)
    actual val cameraEnabled: StateFlow<Boolean> = _cam

    // Треки для рендера (CallVideoView их читает)
    internal val localVideo = MutableStateFlow<VideoTrack?>(null)
    internal val remoteVideo = MutableStateFlow<VideoTrack?>(null)

    actual suspend fun connect(url: String, token: String, video: Boolean, publishMic: Boolean) {
        _state.value = CallMediaState.Connecting
        try {
            val r = LiveKit.create(AndroidAppContext.app.applicationContext)
            room = r
            scope.launch {
                r.events.collect { ev ->
                    when (ev) {
                        is RoomEvent.TrackSubscribed -> (ev.track as? VideoTrack)?.let { remoteVideo.value = it }
                        is RoomEvent.TrackUnsubscribed -> if (remoteVideo.value === ev.track) remoteVideo.value = null
                        is RoomEvent.Disconnected -> _state.value = CallMediaState.Idle
                        else -> {}
                    }
                }
            }
            r.connect(url, token)
            r.localParticipant.setMicrophoneEnabled(publishMic)
            _mic.value = publishMic
            if (video) {
                r.localParticipant.setCameraEnabled(true)
                _cam.value = true
                localVideo.value = r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            }
            _state.value = CallMediaState.Connected
        } catch (e: Throwable) {
            _state.value = CallMediaState.Failed
            disconnect()
        }
    }

    actual fun setMic(on: Boolean) {
        val r = room ?: return
        scope.launch {
            r.localParticipant.setMicrophoneEnabled(on)
            _mic.value = on
        }
    }

    actual fun setCamera(on: Boolean) {
        val r = room ?: return
        scope.launch {
            r.localParticipant.setCameraEnabled(on)
            _cam.value = on
            localVideo.value = if (on) {
                r.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            } else null
        }
    }

    actual fun disconnect() {
        room?.disconnect()
        room = null
        localVideo.value = null
        remoteVideo.value = null
        if (_state.value != CallMediaState.Failed) _state.value = CallMediaState.Idle
    }

    /** Инициализировать рендерер EGL-контекстом комнаты (нужно перед attach трека). */
    internal fun initRenderer(view: TextureViewRenderer) {
        room?.initVideoRenderer(view)
    }
}

@Composable
actual fun CallVideoView(engine: CallEngine, modifier: Modifier) {
    val remote by engine.remoteVideo.collectAsState()
    val local by engine.localVideo.collectAsState()
    Box(modifier) {
        remote?.let { track ->
            key(track) { TrackRenderer(engine, track, Modifier.fillMaxSize()) }
        }
        local?.let { track ->
            key(track) {
                TrackRenderer(
                    engine, track,
                    Modifier.align(Alignment.BottomEnd).padding(12.dp).size(110.dp, 150.dp),
                )
            }
        }
    }
}

@Composable
private fun TrackRenderer(engine: CallEngine, track: VideoTrack, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureViewRenderer(ctx).also { view ->
                engine.initRenderer(view)
                track.addRenderer(view)
            }
        },
        onRelease = { view ->
            track.removeRenderer(view)
            view.release()
        },
    )
}
