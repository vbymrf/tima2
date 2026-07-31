package io.tima.app.platform

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.collectAsState
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.audio.AudioSwitchHandler
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
    private val _speaker = MutableStateFlow(false)
    actual val speakerOn: StateFlow<Boolean> = _speaker
    private val _peer = MutableStateFlow(false)
    actual val peerPresent: StateFlow<Boolean> = _peer

    // Треки для рендера (CallVideoView их читает)
    internal val localVideo = MutableStateFlow<VideoTrack?>(null)
    internal val remoteVideo = MutableStateFlow<VideoTrack?>(null)

    /**
     * Все удалённые участники с их видео. Для звонка один на один хватало одного
     * трека, для группового — нет: второй вошедший затирал первого, и на экране
     * оставался кто-то один.
     *
     * Ключ — identity участника из токена LiveKit (там лежит user_id).
     */
    private val _participants = MutableStateFlow<List<CallParticipant>>(emptyList())
    actual val participants: StateFlow<List<CallParticipant>> = _participants

    private fun refreshParticipants(r: Room) {
        _participants.value = r.remoteParticipants.values.map { p ->
            CallParticipant(
                identity = p.identity?.value.orEmpty(),
                name = p.name.orEmpty(),
                speaking = p.isSpeaking,
                micOn = p.getTrackPublication(Track.Source.MICROPHONE)?.muted == false,
            )
        }
        remoteTracks.value = r.remoteParticipants.values.mapNotNull { p ->
            val t = p.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack ?: return@mapNotNull null
            (p.identity?.value.orEmpty()) to t
        }
    }

    /** identity → видеотрек; читает сетка участников. */
    internal val remoteTracks = MutableStateFlow<List<Pair<String, VideoTrack>>>(emptyList())

    actual suspend fun connect(url: String, token: String, video: Boolean, publishMic: Boolean) {
        _state.value = CallMediaState.Connecting
        try {
            // Поднимаем ДО подключения: пока сервис не в foreground, система вправе
            // отобрать микрофон, как только экран погаснет
            CallService.start(AndroidAppContext.app, video)
            val r = LiveKit.create(AndroidAppContext.app.applicationContext)
            room = r
            // Маршрут звука. По умолчанию AudioSwitch ставит Earpiece выше Speakerphone —
            // звонок уходит в динамик «у уха», и при взгляде на экран его не слышно
            // (голосовые сообщения слышно, потому что они идут музыкальным потоком мимо
            // LiveKit). Видео — сразу громкий динамик; аудио — как в телефоне, к уху,
            // но с кнопкой переключения.
            speakerToSpeakerphone(r, video)
            _speaker.value = video
            scope.launch {
                r.events.collect { ev ->
                    when (ev) {
                        is RoomEvent.TrackSubscribed -> {
                            (ev.track as? VideoTrack)?.let { remoteVideo.value = it }
                            refreshParticipants(r)
                        }
                        is RoomEvent.TrackUnsubscribed -> {
                            if (remoteVideo.value === ev.track) remoteVideo.value = null
                            refreshParticipants(r)
                        }
                        // Собеседник реально в комнате — по этому и глушим гудок дозвона
                        is RoomEvent.ParticipantConnected -> {
                            _peer.value = true
                            refreshParticipants(r)
                        }
                        is RoomEvent.ParticipantDisconnected -> {
                            _peer.value = r.remoteParticipants.isNotEmpty()
                            refreshParticipants(r)
                        }
                        // Кто говорит — подсветка в сетке. В группе из пяти человек
                        // без неё непонятно, кого слушаешь.
                        is RoomEvent.ActiveSpeakersChanged -> refreshParticipants(r)
                        is RoomEvent.TrackMuted, is RoomEvent.TrackUnmuted -> refreshParticipants(r)
                        is RoomEvent.Disconnected -> {
                            _peer.value = false
                            _participants.value = emptyList()
                            remoteTracks.value = emptyList()
                            _state.value = CallMediaState.Idle
                        }
                        else -> {}
                    }
                }
            }
            r.connect(url, token)
            // Успели зайти вторыми — события ParticipantConnected уже не будет
            _peer.value = r.remoteParticipants.isNotEmpty()
            refreshParticipants(r)
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

    /** Переставляет порядок предпочтений AudioSwitch: гарнитура всегда важнее выбора «ухо/громкий». */
    private fun speakerToSpeakerphone(r: Room, speaker: Boolean) {
        val handler = r.audioHandler as? AudioSwitchHandler ?: return
        handler.preferredDeviceList = if (speaker) {
            listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java,
            )
        } else {
            listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java,
                AudioDevice.Speakerphone::class.java,
            )
        }
    }

    actual fun setSpeaker(on: Boolean) {
        val r = room ?: return
        speakerToSpeakerphone(r, on)
        // Список предпочтений влияет на ВЫБОР устройства — переключаем явно
        val handler = r.audioHandler as? AudioSwitchHandler
        val want = if (on) AudioDevice.Speakerphone::class.java else AudioDevice.Earpiece::class.java
        handler?.availableAudioDevices?.firstOrNull { want.isInstance(it) }?.let { handler.selectDevice(it) }
        _speaker.value = on
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
        _peer.value = false
        CallService.stop(AndroidAppContext.app)
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

/**
 * Сетка участников. Плиток столько же, сколько людей в звонке, — включая тех, у
 * кого камера выключена: иначе в аудиозвонке экран пустой и непонятно, кто здесь.
 *
 * Раскладка простая: чем больше народу, тем больше столбцов. Ограничение комнаты
 * — 20 человек (livekit.yaml), при таком числе плитки становятся мелкими, но
 * читаемыми.
 */
@Composable
actual fun CallGridView(engine: CallEngine, names: Map<String, String>, modifier: Modifier) {
    val people by engine.participants.collectAsState()
    val tracks by engine.remoteTracks.collectAsState()
    val local by engine.localVideo.collectAsState()
    val byIdentity = remember(tracks) { tracks.toMap() }

    val columns = when {
        people.size <= 1 -> 1
        people.size <= 3 -> 2
        else -> 3
    }
    Column(modifier) {
        // Своё видео отдельной строкой сверху — так же, как в звонке один на один
        // человек привык видеть себя.
        local?.let { track ->
            key(track) {
                TrackRenderer(engine, track, Modifier.fillMaxWidth().height(120.dp).padding(2.dp))
            }
        }
        people.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                row.forEach { p ->
                    val label = names[p.identity] ?: p.name.ifEmpty { p.identity.take(8) + "…" }
                    Box(
                        Modifier.weight(1f).padding(2.dp).height(160.dp)
                            .background(
                                if (p.speaking) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    ) {
                        byIdentity[p.identity]?.let { track ->
                            key(track) { TrackRenderer(engine, track, Modifier.fillMaxSize()) }
                        }
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (!p.micOn) Text("🔇 ", style = MaterialTheme.typography.labelSmall)
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // Добиваем ряд пустотой, иначе последняя плитка растянется на всю ширину
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        if (people.isEmpty()) {
            Text(
                "Пока никто не подключился",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
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
