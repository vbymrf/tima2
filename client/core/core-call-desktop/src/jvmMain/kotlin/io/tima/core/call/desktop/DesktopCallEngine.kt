package io.tima.core.call.desktop

import com.sun.jna.Pointer
import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.CallQuality
import io.tima.core.call.CallStage
import io.tima.core.call.CallState
import io.tima.core.call.PictureVideo
import io.tima.core.call.PublishPreset
import io.tima.core.call.VideoHandle
import io.tima.core.call.VideoPicture
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import livekit.proto.AudioEncoding
import livekit.proto.AudioSourceOptions
import livekit.proto.AudioSourceType
import livekit.proto.ConnectRequest
import livekit.proto.ConnectionQuality
import livekit.proto.ConnectionState
import livekit.proto.CreateAudioTrackRequest
import livekit.proto.DisconnectRequest
import livekit.proto.FfiEvent
import livekit.proto.FfiRequest
import livekit.proto.GetAudioDevicesRequest
import livekit.proto.LocalTrackMuteRequest
import livekit.proto.NewAudioSourceRequest
import livekit.proto.NewPlatformAudioRequest
import livekit.proto.NewVideoStreamRequest
import livekit.proto.OwnedTrack
import livekit.proto.OwnedTrackPublication
import livekit.proto.PublishTrackRequest
import livekit.proto.ReadyForRoomEventRequest
import livekit.proto.RoomEvent
import livekit.proto.RoomOptions
import livekit.proto.SetSubscribedRequest
import livekit.proto.TrackKind
import livekit.proto.TrackPublishOptions
import livekit.proto.TrackSource
import livekit.proto.VideoBufferType
import livekit.proto.VideoRotation
import livekit.proto.VideoStreamEvent
import livekit.proto.VideoStreamType

/**
 * Звонок на ПК — `livekit-ffi`, маршрут A (doc_mig/ПЛАН-ЗВОНКОВ-ПК.md).
 *
 * Ведёт себя как `LiveKitCallEngine` на Android — те же стадии, те же слова в журнале,
 * те же правила «кто в комнате» и «разговор кончился». Отличается устройством: SDK здесь
 * не объект Kotlin, а сервер внутри нативной библиотеки, и говорим мы с ним сообщениями.
 *
 * ── ЗВУК ДЕЛАЕТ LIBWEBRTC, А НЕ МЫ ──────────────────────────────────────────
 *
 * `PlatformAudio` включает ADM WebRTC — тот же модуль, что работает с микрофоном на
 * Android. Он сам берёт микрофон Windows, сам играет собеседника в колонки и **сам
 * подаёт сыгранное в эхоподавление**: образец для APM у него в руках. Своего захвата и
 * вывода звука у нас нет, и это главное, что спасает маршрут A (§2б плана).
 *
 * ── ПОТОКИ ──────────────────────────────────────────────────────────────────
 *
 * Всё состояние движка меняется на одном потоке ([worker]): события комнаты приходят из
 * потоков библиотеки, и обработка их там же дала бы гонки с командами экрана. Исключение
 * одно — кадры видео: они разбираются прямо в потоке библиотеки, потому что их тридцать в
 * секунду и очередь им только мешает.
 */
class DesktopCallEngine private constructor(private val scope: CoroutineScope) : CallEngine {

    companion object {
        /**
         * Движок — если библиотека на месте. Иначе `null`, и ПК остаётся без звонков
         * честно (ПК0): «Принять» не показывается вовсе.
         */
        fun createOrNull(scope: CoroutineScope): DesktopCallEngine? {
            if (Ffi.libraryFile() == null) {
                Journal.trouble(LogCode.CALL, "нет библиотеки звонков ПК — звонков на этом ПК не будет")
                return null
            }
            return DesktopCallEngine(scope)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow(CallState())
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoHandle?>(null)
    override val localVideo: StateFlow<VideoHandle?> = _localVideo.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoHandle?>(null)
    override val remoteVideo: StateFlow<VideoHandle?> = _remoteVideo.asStateFlow()

    // ── Комната. Всё — ручки библиотеки; 0 — нет. ───────────────────────────
    private var room = 0L
    private var localParticipant = 0L
    private var myIdentity = ""
    private var roomState = ConnectionState.CONN_DISCONNECTED
    private val others = LinkedHashSet<String>()

    /**
     * Всё, что библиотека отдала нам во владение и что надо вернуть по концу звонка.
     *
     * Ручка, не отданная назад, держит объект внутри библиотеки до конца процесса. За
     * день звонков это участники, публикации и дорожки каждого из них — утечка, которой
     * не видно, пока ПК не начнёт тормозить.
     */
    private val owned = ArrayList<Long>()

    /** Чужие публикации по sid: нужны, чтобы отписаться от видео (ЗВ11). */
    private val publications = HashMap<String, Publication>()

    private class Publication(val handle: Long, val kind: TrackKind, val identity: String)

    // ── Звук ────────────────────────────────────────────────────────────────
    private var platformAudio = 0L
    private var micSource = 0L
    private var micTrack = 0L

    // ── Видео собеседника ───────────────────────────────────────────────────
    private class Remote(val sid: String, val track: Long, val stream: Long, val handle: Frames)

    /** Читается потоком библиотеки при каждом кадре — отсюда `@Volatile`. */
    @Volatile
    private var remote: Remote? = null

    private var takeRemote = true
    private var everAnswered = false
    private var brokeAt = 0L

    private val events = Channel<RoomEvent>(Channel.UNLIMITED)
    private var pump: Job? = null

    /**
     * Слушатель событий библиотеки. Кадры — сразу, комната — в очередь на [worker].
     * Ждать здесь нельзя: это поток библиотеки.
     */
    private val listener: (FfiEvent) -> Unit = { event ->
        event.video_stream_event?.let { onVideo(it) }
        event.room_event?.let { events.trySend(it) }
    }

    override suspend fun connect(door: CallDoor, publish: PublishPreset?) = withContext(worker) {
        Ffi.start()?.let { why ->
            Journal.trouble(LogCode.CALL, "движок звонков ПК не загрузился", "причина" to why)
            _state.value = CallState(stage = CallStage.Ended, callId = door.callId, trouble = why)
            return@withContext
        }
        closeRoom()
        everAnswered = false
        takeRemote = true
        _state.value = CallState(stage = CallStage.Connecting, callId = door.callId)
        Ffi.listen(listener)
        pump = scope.launch(worker) { for (event in events) onRoom(event) }

        openAudio()

        val answer = Ffi.call(CONNECT_TIMEOUT_MS) { id ->
            FfiRequest(
                connect = ConnectRequest(
                    url = door.url,
                    token = door.token,
                    options = RoomOptions(
                        auto_subscribe = true,
                        adaptive_stream = publish?.video?.adaptiveStream ?: true,
                        dynacast = publish?.video?.dynacast ?: true,
                    ),
                    request_async_id = id,
                ),
            )
        }?.connect
        val result = answer?.result
        if (result == null) {
            val why = answer?.error ?: "нет ответа за ${CONNECT_TIMEOUT_MS / 1000} с"
            // Причина словами и в состоянии: звонок, который «просто не начался», — худшее
            // из состояний (то же правило, что на Android).
            Journal.trouble(LogCode.CALL, "в комнату не вошли", "причина" to why)
            closeRoom()
            _state.value = _state.value.copy(stage = CallStage.Ended, trouble = why)
            return@withContext
        }
        room = result.room.handle.id
        owned += room
        localParticipant = result.local_participant.handle.id
        owned += localParticipant
        myIdentity = result.local_participant.info.identity
        for (who in result.participants) {
            owned += who.participant.handle.id
            others += who.participant.info.identity
            who.publications.forEach { remember(who.participant.info.identity, it) }
        }
        roomState = ConnectionState.CONN_CONNECTED
        // События комнаты библиотека держит у себя, пока мы не скажем «готов»: иначе
        // подписка на дорожку собеседника пришла бы раньше, чем мы узнали номер комнаты.
        Ffi.request(FfiRequest(ready_for_room_event = ReadyForRoomEventRequest(room_handle = room)))
        Journal.note(LogCode.CALL, "вошли в комнату", "комната" to door.room)
        settle(door.callId)
        publishMicrophone(publish)
    }

    /**
     * Микрофон и колонки через ADM WebRTC.
     *
     * Устройства пишутся в журнал **именами**: «звука нет» на ПК — это чаще всего не тот
     * микрофон, и без названия его не отличить от сломанного.
     */
    private fun openAudio() {
        if (platformAudio != 0L) return
        val answer = runCatching {
            Ffi.request(FfiRequest(new_platform_audio = NewPlatformAudioRequest())).new_platform_audio
        }.getOrNull()
        val audio = answer?.platform_audio
        if (audio == null) {
            val why = answer?.error ?: "звук ПК не открылся"
            Journal.trouble(LogCode.CALL_DEVICE, "микрофон и колонки не открылись", "причина" to why)
            _state.value = _state.value.copy(notice = why)
            return
        }
        platformAudio = audio.handle.id
        val devices = runCatching {
            Ffi.request(
                FfiRequest(get_audio_devices = GetAudioDevicesRequest(platform_audio_handle = platformAudio)),
            ).get_audio_devices
        }.getOrNull()
        Journal.note(
            LogCode.CALL_DEVICE, "звук ПК открыт",
            "микрофонов" to audio.info.recording_device_count,
            "колонок" to audio.info.playout_device_count,
            "микрофоны" to devices?.recording_devices?.joinToString(" | ") { it.name }.orEmpty(),
        )
        if (audio.info.recording_device_count == 0) {
            // Звонок идёт и без микрофона — собеседника слышно. Но сказать надо сразу, а
            // не ждать, пока он спросит «ты меня слышишь?».
            _state.value = _state.value.copy(notice = "на ПК нет микрофона")
        }
    }

    /**
     * Поднять микрофон — отдельно от входа и с повтором, по тем же причинам, что на
     * Android: отказ дорожки — не «в комнату не вошли», и публикующее соединение может
     * быть ещё не готово.
     */
    private suspend fun publishMicrophone(publish: PublishPreset?) {
        if (platformAudio == 0L) return
        if (micTrack == 0L) {
            micSource = Ffi.request(
                FfiRequest(
                    new_audio_source = NewAudioSourceRequest(
                        type = AudioSourceType.AUDIO_SOURCE_PLATFORM,
                        // Эхо, шум и усиление — APM WebRTC. Нейросеть поверх — срез ПК7.
                        options = AudioSourceOptions(
                            echo_cancellation = true,
                            noise_suppression = true,
                            auto_gain_control = true,
                        ),
                        platform_audio_handle = platformAudio,
                    ),
                ),
            ).new_audio_source?.source?.handle?.id ?: 0L
            micTrack = Ffi.request(
                FfiRequest(create_audio_track = CreateAudioTrackRequest(name = "microphone", source_handle = micSource)),
            ).create_audio_track?.track?.handle?.id ?: 0L
        }
        var last = "дорожка не поднялась"
        repeat(PUBLISH_TRIES) { attemptNo ->
            val answer = Ffi.call(PUBLISH_TIMEOUT_MS) { id ->
                FfiRequest(
                    publish_track = PublishTrackRequest(
                        local_participant_handle = localParticipant,
                        track_handle = micTrack,
                        options = TrackPublishOptions(
                            source = TrackSource.SOURCE_MICROPHONE,
                            dtx = publish?.audio?.dtx ?: true,
                            red = publish?.audio?.red ?: true,
                            audio_encoding = publish?.audio?.bitrate?.let { AudioEncoding(max_bitrate = it.toLong()) },
                        ),
                        request_async_id = id,
                    ),
                )
            }?.publish_track
            val publication = answer?.publication
            if (publication != null) {
                owned += publication.handle.id
                _state.value = _state.value.copy(microphoneOn = true)
                Journal.note(LogCode.CALL, "микрофон опубликован", "попытка" to attemptNo + 1)
                return
            }
            last = answer?.error ?: "нет ответа"
            delay(PUBLISH_RETRY_MS)
        }
        Journal.trouble(LogCode.CALL, "микрофон не опубликован", "причина" to last)
        _state.value = _state.value.copy(notice = last)
    }

    /** Событие комнаты — на [worker], по одному. */
    private fun onRoom(event: RoomEvent) {
        if (event.room_handle != room || room == 0L) return
        val callId = _state.value.callId
        event.participant_connected?.let {
            owned += it.info.handle.id
            others += it.info.info.identity
            settle(callId)
        }
        event.participant_disconnected?.let { gone ->
            others -= gone.participant_identity
            publications.entries.removeAll { (_, p) -> (p.identity == gone.participant_identity).also { if (it) Ffi.drop(p.handle) } }
            settle(callId)
        }
        event.connection_state_changed?.let {
            roomState = it.state
            settle(callId)
        }
        event.reconnecting?.let {
            if (brokeAt == 0L) {
                brokeAt = System.nanoTime()
                Journal.trouble(LogCode.CALL, "связь потеряна, возвращаемся")
            }
        }
        event.reconnected?.let {
            if (brokeAt != 0L) {
                val took = (System.nanoTime() - brokeAt) / 1_000_000_000.0
                brokeAt = 0L
                Journal.note(LogCode.CALL, "связь вернулась", "заняло с" to took.toString())
            }
        }
        event.disconnected?.let {
            Journal.note(LogCode.CALL, "комната закрылась", "причина" to it.reason.name)
            roomState = ConnectionState.CONN_DISCONNECTED
            settle(callId)
        }
        event.connection_quality_changed?.let {
            if (it.participant_identity != myIdentity) return@let
            val ours = when (it.quality) {
                ConnectionQuality.QUALITY_EXCELLENT -> CallQuality.Excellent
                ConnectionQuality.QUALITY_GOOD -> CallQuality.Good
                ConnectionQuality.QUALITY_POOR -> CallQuality.Poor
                ConnectionQuality.QUALITY_LOST -> CallQuality.Lost
            }
            if (ours != _state.value.quality) {
                _state.value = _state.value.copy(quality = ours)
                Journal.note(LogCode.CALL, "оценка связи от SFU", "стала" to ours.name)
            }
        }
        event.track_published?.let {
            remember(it.participant_identity, it.publication)
            // Нажали «скрыть» заранее — чужая камера, включённая потом, к нам не приедет.
            if (!takeRemote && it.publication.info.kind == TrackKind.KIND_VIDEO) {
                subscribe(it.publication.handle.id, false)
            }
        }
        event.track_unpublished?.let { gone ->
            publications.remove(gone.publication_sid)?.let { Ffi.drop(it.handle) }
        }
        event.track_subscribed?.let { onSubscribed(it.track) }
        event.track_unsubscribed?.let { if (remote?.sid == it.track_sid) stopRemote() }
        event.track_muted?.let {
            if (remote?.sid == it.track_sid) _state.value = _state.value.copy(remoteVideoShown = false)
        }
        event.track_unmuted?.let {
            if (remote?.sid == it.track_sid) _state.value = _state.value.copy(remoteVideoShown = true)
        }
    }

    private fun remember(identity: String, publication: OwnedTrackPublication) {
        publications.put(publication.info.sid, Publication(publication.handle.id, publication.info.kind, identity))
            ?.let { Ffi.drop(it.handle) }
    }

    /**
     * Приехала чужая дорожка. Звук играет ADM сам — нам с ним делать нечего. Видео —
     * поток кадров BGRA, рисует его экран.
     */
    private fun onSubscribed(track: OwnedTrack) {
        if (track.info.kind != TrackKind.KIND_VIDEO) {
            owned += track.handle.id
            return
        }
        if (remote != null) stopRemote()
        val stream = runCatching {
            Ffi.request(
                FfiRequest(
                    new_video_stream = NewVideoStreamRequest(
                        track_handle = track.handle.id,
                        type = VideoStreamType.VIDEO_STREAM_NATIVE,
                        format = VideoBufferType.BGRA,
                        // Строка ровно в ширину: иначе на каждом кадре пришлось бы
                        // вырезать добивку, а у экрана нет на это причины.
                        normalize_stride = true,
                    ),
                ),
            ).new_video_stream?.stream?.handle?.id
        }.getOrNull()
        if (stream == null) {
            Journal.trouble(LogCode.CALL, "видео собеседника не открылось")
            Ffi.drop(track.handle.id)
            return
        }
        val handle = Frames()
        remote = Remote(track.info.sid, track.handle.id, stream, handle)
        _remoteVideo.value = handle
        _state.value = _state.value.copy(remoteVideoShown = !track.info.muted)
        Journal.note(LogCode.CALL, "видео собеседника идёт")
    }

    private fun stopRemote() {
        val shown = remote ?: return
        remote = null
        Ffi.drop(shown.stream)
        Ffi.drop(shown.track)
        _remoteVideo.value = null
        _state.value = _state.value.copy(remoteVideoShown = false)
    }

    /**
     * Кадр собеседника — **в потоке библиотеки**, быстро: скопировать, отдать буфер назад,
     * повернуть, если телефон снимал боком.
     */
    private fun onVideo(event: VideoStreamEvent) {
        val frame = event.frame_received ?: return
        try {
            val shown = remote ?: return
            if (event.stream_handle != shown.stream) return
            val info = frame.buffer.info
            val width = info.width
            val height = info.height
            val bytes = Pointer(info.data_ptr).getByteArray(0, width * height * 4)
            shown.handle.pictures.value = turn(VideoPicture(width, height, bytes), frame.rotation)
        } finally {
            Ffi.drop(frame.buffer.handle.id)
        }
    }

    /**
     * Стадия — из состояния комнаты и участников вместе. Правило то же, что на Android:
     * «вошли» не значит «соединили», а пустая комната до ответа и после — разное.
     */
    private fun settle(callId: String) {
        if (others.isNotEmpty()) everAnswered = true
        val stage = when {
            roomState == ConnectionState.CONN_DISCONNECTED -> CallStage.Ended
            roomState == ConnectionState.CONN_RECONNECTING -> CallStage.Reconnecting
            others.isNotEmpty() -> CallStage.Connected
            everAnswered -> CallStage.Ended
            else -> CallStage.Connecting
        }
        _state.value = _state.value.copy(
            callId = callId,
            others = others.toList(),
            inRoom = roomState == ConnectionState.CONN_CONNECTED,
            stage = stage,
            peerLeft = stage == CallStage.Ended && everAnswered && roomState == ConnectionState.CONN_CONNECTED,
        )
    }

    override suspend fun disconnect() = withContext(worker) {
        if (room != 0L) {
            val target = room
            Ffi.call(DISCONNECT_TIMEOUT_MS) { id ->
                FfiRequest(disconnect = DisconnectRequest(room_handle = target, request_async_id = id))
            }
        }
        closeRoom()
        _state.value = _state.value.copy(stage = CallStage.Ended)
    }

    override suspend fun reenter(door: CallDoor, publish: PublishPreset?) {
        // Звонок продолжается — кончается только комната (то же, что на Android).
        withContext(worker) {
            if (room != 0L) {
                val target = room
                Ffi.call(DISCONNECT_TIMEOUT_MS) { id ->
                    FfiRequest(disconnect = DisconnectRequest(room_handle = target, request_async_id = id))
                }
            }
            closeRoom()
        }
        connect(door, publish)
    }

    /**
     * Вернуть библиотеке всё, что брали за звонок, и погасить слушателей. Идемпотентно.
     *
     * Звук закрывается тоже: пока жива ручка `PlatformAudio`, Windows показывает, что
     * микрофон занят, — а звонок кончился.
     */
    private fun closeRoom() {
        Ffi.unlisten(listener)
        pump?.cancel()
        pump = null
        while (events.tryReceive().isSuccess) Unit
        stopRemote()
        publications.values.forEach { Ffi.drop(it.handle) }
        publications.clear()
        owned.asReversed().forEach { Ffi.drop(it) }
        owned.clear()
        Ffi.drop(micTrack)
        Ffi.drop(micSource)
        Ffi.drop(platformAudio)
        micTrack = 0L
        micSource = 0L
        platformAudio = 0L
        room = 0L
        localParticipant = 0L
        myIdentity = ""
        others.clear()
        roomState = ConnectionState.CONN_DISCONNECTED
        brokeAt = 0L
        _localVideo.value = null
    }

    override suspend fun setMicrophone(on: Boolean) = withContext(worker) {
        if (micTrack == 0L) return@withContext
        val done = runCatching {
            Ffi.request(FfiRequest(local_track_mute = LocalTrackMuteRequest(track_handle = micTrack, mute = !on)))
        }.isSuccess
        // Состояние меняется, только если дорожка действительно приглушена.
        if (done) _state.value = _state.value.copy(microphoneOn = on)
    }

    override suspend fun setCamera(on: Boolean) = withContext(worker) {
        if (!on) return@withContext
        // Камера на ПК — срез ПК4. До него честно: сказать, а не молча не включить.
        Journal.note(LogCode.CALL, "камера на ПК ещё не подключена")
        _state.value = _state.value.copy(notice = "камера на ПК ещё не подключена")
    }

    override suspend fun setRemoteVideo(on: Boolean) = withContext(worker) {
        takeRemote = on
        for (p in publications.values) {
            if (p.kind == TrackKind.KIND_VIDEO) subscribe(p.handle, on)
        }
        if (!on) stopRemote()
        _state.value = _state.value.copy(remoteVideoTaken = on)
    }

    private fun subscribe(publication: Long, on: Boolean) {
        runCatching {
            Ffi.request(FfiRequest(set_subscribed = SetSubscribedRequest(subscribe = on, publication_handle = publication)))
        }.onFailure {
            Journal.trouble(LogCode.CALL, "подписка на видео не сменилась", "причина" to (it.message ?: "?"))
        }
    }
}

/** Ручка видео ПК: последний кадр потоком. */
internal class Frames : PictureVideo {
    override val pictures = MutableStateFlow<VideoPicture?>(null)
}

/**
 * Повернуть кадр, если его сняли боком.
 *
 * Телефон шлёт кадры как их видит датчик и отдельно — поворот: так дешевле, чем вращать
 * каждый кадр на телефоне. Android показывает их поверхностью libwebrtc, которая поворот
 * учитывает сама; у нас поверхности нет, и вращаем мы.
 */
internal fun turn(picture: VideoPicture, rotation: VideoRotation): VideoPicture {
    if (rotation == VideoRotation.VIDEO_ROTATION_0) return picture
    val w = picture.width
    val h = picture.height
    val src = picture.bgra
    val out = ByteArray(src.size)
    val turned = rotation == VideoRotation.VIDEO_ROTATION_90 || rotation == VideoRotation.VIDEO_ROTATION_270
    val outW = if (turned) h else w
    for (y in 0 until h) {
        for (x in 0 until w) {
            val (nx, ny) = when (rotation) {
                VideoRotation.VIDEO_ROTATION_90 -> (h - 1 - y) to x
                VideoRotation.VIDEO_ROTATION_180 -> (w - 1 - x) to (h - 1 - y)
                VideoRotation.VIDEO_ROTATION_270 -> y to (w - 1 - x)
                VideoRotation.VIDEO_ROTATION_0 -> x to y
            }
            System.arraycopy(src, (y * w + x) * 4, out, (ny * outW + nx) * 4, 4)
        }
    }
    return VideoPicture(outW, if (turned) w else h, out)
}

private const val CONNECT_TIMEOUT_MS = 20_000L
private const val DISCONNECT_TIMEOUT_MS = 5_000L
private const val PUBLISH_TIMEOUT_MS = 10_000L
private const val PUBLISH_TRIES = 3
private const val PUBLISH_RETRY_MS = 500L
