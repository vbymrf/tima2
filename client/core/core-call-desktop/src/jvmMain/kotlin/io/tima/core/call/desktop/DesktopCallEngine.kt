package io.tima.core.call.desktop

import com.sun.jna.Pointer
import io.tima.core.call.CallDoor
import io.tima.core.call.CallEngine
import io.tima.core.call.CallQuality
import io.tima.core.call.CallStage
import io.tima.core.call.CallDevices
import io.tima.core.call.CallSetup
import io.tima.core.call.CallState
import io.tima.core.call.Degradation
import io.tima.core.call.LayerMode
import io.tima.core.call.VideoCodec
import io.tima.core.call.VideoPreset
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import livekit.proto.CaptureVideoFrameRequest
import livekit.proto.SetPlayoutDeviceRequest
import livekit.proto.SetRecordingDeviceRequest
import livekit.proto.CreateAudioTrackRequest
import livekit.proto.CreateVideoTrackRequest
import livekit.proto.DegradationPreference
import livekit.proto.NewVideoSourceRequest
import livekit.proto.VideoBufferInfo
import livekit.proto.VideoEncoding
import livekit.proto.VideoSourceResolution
import livekit.proto.VideoSourceType
import livekit.proto.VideoCodec as LkVideoCodec
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
import livekit.proto.GetStatsRequest
import io.tima.core.call.RemoteVideoWatch
import io.tima.core.call.RemoteVideoLoss
import io.tima.core.call.CodecChoice
import io.tima.core.call.PeerCodecs
import livekit.proto.AttributesEntry
import livekit.proto.SetLocalAttributesRequest
import livekit.proto.UnpublishTrackRequest
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
class DesktopCallEngine private constructor(private val scope: CoroutineScope) : CallEngine, CallDevices {

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

    /** Камеры, которые собеседник выключил, — по `track_muted`; для [watchLoss]. */
    private val mutedSids = HashSet<String>()

    /** Наблюдатель «видео собеседника нет» — см. [watchLoss]. */
    private var lossJob: Job? = null

    // ── Кто что раскодирует (ПЛАН-ВИДЕО.md В5) ─────────────────────────────
    /** Что сказал каждый собеседник; нет ключа — ещё не сказал. */
    private val peerCodecs = HashMap<String, Set<VideoCodec>>()

    /** Когда впервые увидели собеседника без слова — ждём [PeerCodecs.WAIT_MS]. */
    private val peerSeen = HashMap<String, Long>()

    /** Собеседники, про которых в журнал уже написано «не сказал». */
    private val peerSilent = HashSet<String>()
    private var peersJob: Job? = null

    /** Публикация камеры: sid — чтобы снять её при смене кодека, и её кодек. */
    private var cameraSid = ""
    private var cameraCodec: VideoCodec? = null

    // ── Звук ────────────────────────────────────────────────────────────────
    private var platformAudio = 0L
    private var micSource = 0L
    private var micTrack = 0L

    // ── Камера (ПК4) ────────────────────────────────────────────────────────
    private var camera: Camera? = null
    private var cameraSource = 0L
    private var cameraTrack = 0L
    private var cameraJob: Job? = null

    /** Пресет публикации звонка: кодек и полоса камеры берутся из него. */
    private var preset: PublishPreset? = null

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
        preset = publish
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
            heard(who.participant.info.identity, who.participant.info.attributes)
        }
        roomState = ConnectionState.CONN_CONNECTED
        // События комнаты библиотека держит у себя, пока мы не скажем «готов»: иначе
        // подписка на дорожку собеседника пришла бы раньше, чем мы узнали номер комнаты.
        Ffi.request(FfiRequest(ready_for_room_event = ReadyForRoomEventRequest(room_handle = room)))
        Journal.note(LogCode.CALL, "вошли в комнату", "комната" to door.room)
        settle(door.callId)
        announceDecoding()
        publishMicrophone(publish)
        watchLoss()
        watchPeers()
    }

    /**
     * Сказать собеседникам, что ПК раскодирует (ПЛАН-ВИДЕО.md В5), и записать, чем он
     * кодирует и раскодирует (В1). Всё программное: аппаратных кодеров в сборке
     * `livekit-ffi` под Windows нет (ADR-0031). AV1 ПК объявляет, но раскодировщика AV1
     * у него нет — в «принимаем» его нет.
     */
    private suspend fun announceDecoding() {
        Journal.note(
            LogCode.CALL, "кодеки ПК",
            *VideoCodec.entries.map { codec ->
                codec.name to ("код " + (if (codec in PC_ENCODES) "прог" else "нет") +
                    " · раскод " + (if (codec in PC_DECODES) "прог" else "нет"))
            }.toTypedArray(),
        )
        val answer = Ffi.call(PUBLISH_TIMEOUT_MS) { id ->
            FfiRequest(
                set_local_attributes = SetLocalAttributesRequest(
                    local_participant_handle = localParticipant,
                    attributes = listOf(AttributesEntry(key = PeerCodecs.ATTRIBUTE, value_ = PeerCodecs.write(PC_DECODES))),
                    request_async_id = id,
                ),
            )
        }?.set_local_attributes
        if (answer == null || answer.error != null) {
            Journal.trouble(LogCode.CALL, "не сказали собеседнику, что принимаем", "причина" to (answer?.error ?: "нет ответа"))
        }
        Journal.note(LogCode.CALL, "принимаем видео", "кодеки" to PC_DECODES.joinToString(", ") { it.name })
    }

    /** Собеседник сказал, что принимает, — или сказал заново. */
    private fun heard(identity: String, attributes: Map<String, String>) {
        val told = PeerCodecs.read(attributes[PeerCodecs.ATTRIBUTE]) ?: return
        if (peerCodecs.put(identity, told) != told) {
            Journal.note(LogCode.CALL, "собеседник принимает", "кодеки" to told.joinToString(", ") { it.name })
        }
    }

    /**
     * Кодек под собеседников. `null` — решать пока нечего: прогон стенда (кодек набора —
     * закон) или собеседник вошёл и ещё не сказал.
     */
    private fun codecForPeers(): VideoCodec? {
        val publish = preset ?: return null
        if (publish.exact) return null
        val now = System.currentTimeMillis()
        val peers = mutableListOf<Set<VideoCodec>?>()
        for (identity in others) {
            val told = peerCodecs[identity]
            if (told != null) {
                peers += told
                continue
            }
            val seen = peerSeen.getOrPut(identity) { now }
            if (now - seen < PeerCodecs.WAIT_MS) return null
            if (peerSilent.add(identity)) {
                Journal.trouble(LogCode.CALL, "собеседник не сказал, что принимает — шлём VP8", "ждали мс" to PeerCodecs.WAIT_MS)
            }
            peers += null
        }
        return PeerCodecs.choose(publish.video.codec, PC_ENCODES, peers)
    }

    /**
     * Пересматривать кодек камеры посреди звонка — собеседник вошёл, сказал, что примет,
     * или не сказал ничего за [PeerCodecs.WAIT_MS]. Опубликованную камеру — переопубликовать.
     */
    private fun watchPeers() {
        peersJob?.cancel()
        peersJob = scope.launch(worker) {
            while (isActive) {
                delay(PEERS_EVERY_MS)
                if (cameraSid.isEmpty()) continue
                val codec = codecForPeers() ?: continue
                if (codec == cameraCodec) continue
                Journal.note(LogCode.CALL, "кодек сменён под собеседника", "был" to (cameraCodec?.name ?: "—"), "стал" to codec.name)
                republishCamera(codec)
            }
        }
    }

    /**
     * Снять публикацию камеры и опубликовать ту же дорожку другим кодеком. Кодек
     * выбирается при публикации, у живой его не сменить; дорожка и источник остаются —
     * камера не закрывается.
     */
    private suspend fun republishCamera(codec: VideoCodec) {
        val sid = cameraSid
        val answer = Ffi.call(PUBLISH_TIMEOUT_MS) { id ->
            FfiRequest(
                unpublish_track = UnpublishTrackRequest(
                    local_participant_handle = localParticipant,
                    track_sid = sid,
                    stop_on_unpublish = false,
                    request_async_id = id,
                ),
            )
        }?.unpublish_track
        if (answer == null || answer.error != null) {
            Journal.trouble(LogCode.CALL, "публикацию камеры не сняли", "причина" to (answer?.error ?: "нет ответа"))
            return
        }
        cameraSid = ""
        val video = preset?.video ?: VideoPreset()
        if (publishCameraTrack(video, codec)) Journal.note(LogCode.CALL, "камера переопубликована", "кодек" to codec.name)
    }

    /**
     * Видео собеседника нет, хотя он его показывает, — и почему ([RemoteVideoWatch],
     * заказчик 2026-09-29: «делаем и для ПК»). Правило общее с телефоном, данные — из
     * статистики `livekit-ffi`: байты и кадры входящего видео и его кодек.
     *
     * На ПК это важнее, чем кажется: он заявляет, что показывает AV1, а декодера AV1 в его
     * сборке нет (ADR-0031) — такое видео придёт и не покажется.
     */
    private fun watchLoss() {
        lossJob?.cancel()
        lossJob = scope.launch(worker) {
            val watch = RemoteVideoWatch()
            var told: RemoteVideoLoss? = null
            var decoderSaid = ""
            var froze = 0
            while (isActive) {
                delay(LOSS_EVERY_MS)
                val published = publications.entries.any { (sid, p) ->
                    p.kind == TrackKind.KIND_VIDEO && p.identity != myIdentity && sid !in mutedSids
                }
                var bytes: Long? = null
                var frames: Long? = null
                var codec: String? = null
                remote?.let { shown ->
                    val stats = runCatching {
                        Ffi.call(STATS_TIMEOUT_MS) { id ->
                            FfiRequest(get_stats = GetStatsRequest(track_handle = shown.track, request_async_id = id))
                        }?.get_stats?.stats
                    }.getOrNull().orEmpty()
                    val inbound = stats.mapNotNull { it.inbound_rtp }.firstOrNull { it.stream.kind == "video" }
                    bytes = inbound?.inbound?.bytes_received
                    frames = inbound?.inbound?.frames_decoded?.toLong()
                    // Чем раскодируется и замирало ли (ПЛАН-ВИДЕО.md В1) — только смену.
                    inbound?.inbound?.let { got ->
                        if (got.decoder_implementation.isNotBlank() && got.decoder_implementation != decoderSaid) {
                            decoderSaid = got.decoder_implementation
                            val decoded = got.frames_decoded
                            Journal.note(
                                LogCode.CALL, "раскодировщик видео собеседника",
                                "имя" to got.decoder_implementation,
                                "раскод мс" to if (decoded > 0) (Math.round(got.total_decode_time * 10_000 / decoded) / 10.0) else "—",
                                "выброшено" to got.frames_dropped,
                            )
                        }
                        if (got.freeze_count > froze) {
                            froze = got.freeze_count
                            Journal.trouble(LogCode.CALL, "видео собеседника замирало", "раз" to got.freeze_count)
                        }
                    }
                    codec = inbound?.stream?.codec_id?.let { id ->
                        stats.mapNotNull { it.codec }.firstOrNull { it.rtc.id == id }?.codec?.mime_type?.removePrefix("video/")
                    }
                }
                val now = _state.value
                val loss = watch.next(
                    RemoteVideoWatch.Poll(
                        published = published,
                        // Скрыто нами или погашено сервером — у этого свои события.
                        excused = !now.remoteVideoTaken || now.videoPaused || now.stage != CallStage.Connected,
                        bytes = bytes,
                        frames = frames,
                        codec = codec,
                    ),
                )
                if (loss == told) continue
                told = loss
                _state.value = _state.value.copy(remoteVideoLoss = loss)
                when (loss) {
                    null -> Journal.note(LogCode.CALL, "видео собеседника снова показывается")
                    RemoteVideoLoss.NotArriving -> Journal.trouble(LogCode.CALL, "видео собеседника не приходит", "показывает" to published)
                    is RemoteVideoLoss.NotDecoding -> Journal.trouble(LogCode.CALL, "видео собеседника не раскодируется", "кодек" to loss.codec)
                }
            }
        }
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
        // Выбор человека (настройка «Микрофон и камера») — до создания дорожки: ADM
        // пишет с того устройства, что выбрано в момент публикации.
        pickDevices(platformAudio)
        Journal.note(
            LogCode.CALL_DEVICE, "звук ПК открыт",
            "микрофонов" to audio.info.recording_device_count,
            "колонок" to audio.info.playout_device_count,
            "микрофоны" to devices?.recording_devices?.joinToString(" | ") { it.name }.orEmpty(),
            "выбран" to (setup.microphone?.let { id -> devices?.recording_devices?.firstOrNull { it.guid == id }?.name } ?: "по умолчанию"),
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
                        options = setup.audioOptions(),
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
            heard(it.info.info.identity, it.info.info.attributes)
            settle(callId)
        }
        event.participant_attributes_changed?.let { changed ->
            heard(changed.participant_identity, changed.attributes.associate { it.key to it.value_ })
        }
        event.participant_disconnected?.let { gone ->
            others -= gone.participant_identity
            peerCodecs -= gone.participant_identity
            peerSeen -= gone.participant_identity
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
            mutedSids += it.track_sid
            if (remote?.sid == it.track_sid) _state.value = _state.value.copy(remoteVideoShown = false)
        }
        event.track_unmuted?.let {
            mutedSids -= it.track_sid
            if (remote?.sid == it.track_sid) _state.value = _state.value.copy(remoteVideoShown = true)
        }
    }

    private fun remember(identity: String, publication: OwnedTrackPublication) {
        publications.put(publication.info.sid, Publication(publication.handle.id, publication.info.kind, identity))
            ?.let { Ffi.drop(it.handle) }
        if (publication.info.muted) mutedSids += publication.info.sid else mutedSids -= publication.info.sid
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
        lossJob?.cancel()
        lossJob = null
        peersJob?.cancel()
        peersJob = null
        peerCodecs.clear()
        peerSeen.clear()
        peerSilent.clear()
        cameraSid = ""
        cameraCodec = null
        mutedSids.clear()
        while (events.tryReceive().isSuccess) Unit
        stopRemote()
        stopCamera()
        Ffi.drop(cameraTrack)
        Ffi.drop(cameraSource)
        cameraTrack = 0L
        cameraSource = 0L
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

    /**
     * Камера ПК (ПК4). Включение — открыть устройство, при первом разе опубликовать
     * дорожку; выключение — **приглушить дорожку и закрыть устройство**.
     *
     * Приглушение, а не снятие дорожки — по контракту: возврат стоит одного RTT, а не
     * нового обмена SDP. Устройство же закрывается целиком: пока оно открыто, горит
     * лампочка камеры, и человек, выключивший камеру, ей верит больше, чем экрану.
     */
    override suspend fun setCamera(on: Boolean) = withContext(worker) {
        if (!on) {
            stopCamera()
            if (cameraTrack != 0L) mute(cameraTrack, true)
            _state.value = _state.value.copy(cameraOn = false)
            return@withContext
        }
        if (room == 0L || camera != null) return@withContext
        val video = preset?.video ?: VideoPreset()
        val opened = when (val result = Camera.open(video.width, video.height, video.fps, setup.camera)) {
            is Camera.Companion.Opened.Failed -> {
                Journal.trouble(LogCode.CALL_DEVICE, "камера не открылась", "причина" to result.why)
                _state.value = _state.value.copy(notice = result.why)
                return@withContext
            }
            is Camera.Companion.Opened.Ready -> result.camera
        }
        Journal.note(
            LogCode.CALL_DEVICE, "камера открыта",
            "камера" to opened.name, "кадр" to "${opened.width}×${opened.height}", "к/с" to opened.fps,
        )
        if (cameraTrack == 0L && !publishCamera(opened, video)) {
            opened.close()
            return@withContext
        }
        mute(cameraTrack, false)
        camera = opened
        val preview = Frames()
        _localVideo.value = preview
        val source = cameraSource
        cameraJob = scope.launch(Dispatchers.IO) { pumpCamera(opened, source, preview) }
        _state.value = _state.value.copy(cameraOn = true)
    }

    private fun mute(track: Long, muted: Boolean) {
        runCatching { Ffi.request(FfiRequest(local_track_mute = LocalTrackMuteRequest(track_handle = track, mute = muted))) }
    }

    /**
     * Опубликовать дорожку камеры — источник, дорожка, публикация. Кодек и полоса — из
     * пресета, как на Android; без пресета — VP8: его кодирует любой ПК программно, а
     * H.264 в сборке libwebrtc под Windows может и не оказаться.
     */
    private suspend fun publishCamera(opened: Camera, video: VideoPreset): Boolean {
        cameraSource = Ffi.request(
            FfiRequest(
                new_video_source = NewVideoSourceRequest(
                    type = VideoSourceType.VIDEO_SOURCE_NATIVE,
                    resolution = VideoSourceResolution(width = opened.width, height = opened.height),
                ),
            ),
        ).new_video_source?.source?.handle?.id ?: 0L
        cameraTrack = Ffi.request(
            FfiRequest(create_video_track = CreateVideoTrackRequest(name = "camera", source_handle = cameraSource)),
        ).create_video_track?.track?.handle?.id ?: 0L
        // Кодек — под собеседников, если они уже сказали (ПЛАН-ВИДЕО.md В5); никого нет или
        // прогон — по набору и умению ПК. Пересмотрит [watchPeers].
        val publish = preset
        val codec = codecForPeers()
            ?: publish?.let { CodecChoice.pick(video.codec, null, PC_ENCODES, it.exact).chosen }
            ?: VideoCodec.VP8
        if (publishCameraTrack(video, codec)) return true
        Ffi.drop(cameraTrack)
        Ffi.drop(cameraSource)
        cameraTrack = 0L
        cameraSource = 0L
        return false
    }

    /** Опубликовать уже созданную дорожку камеры кодеком [codec]. */
    private suspend fun publishCameraTrack(video: VideoPreset, chosen: VideoCodec): Boolean {
        val codec = chosen.toFfi()
        val answer = Ffi.call(PUBLISH_TIMEOUT_MS) { id ->
            FfiRequest(
                publish_track = PublishTrackRequest(
                    local_participant_handle = localParticipant,
                    track_handle = cameraTrack,
                    options = TrackPublishOptions(
                        source = TrackSource.SOURCE_CAMERA,
                        video_codec = codec,
                        video_encoding = VideoEncoding(
                            max_bitrate = video.bitrate.toLong(),
                            max_framerate = video.fps.toDouble(),
                        ),
                        simulcast = video.layers == LayerMode.Simulcast,
                        degradation_preference = when (video.degradation) {
                            Degradation.MaintainResolution -> DegradationPreference.DEGRADATION_PREFERENCE_MAINTAIN_RESOLUTION
                            Degradation.MaintainFramerate -> DegradationPreference.DEGRADATION_PREFERENCE_MAINTAIN_FRAMERATE
                            Degradation.Balanced -> DegradationPreference.DEGRADATION_PREFERENCE_BALANCED
                        },
                    ),
                    request_async_id = id,
                ),
            )
        }?.publish_track
        val publication = answer?.publication
        if (publication == null) {
            val why = answer?.error ?: "нет ответа"
            Journal.trouble(LogCode.CALL, "камера не опубликована", "причина" to why)
            _state.value = _state.value.copy(notice = why)
            return false
        }
        owned += publication.handle.id
        cameraSid = publication.info.sid
        cameraCodec = chosen
        Journal.note(LogCode.CALL, "камера опубликована", "кодек" to codec.name)
        return true
    }

    /**
     * Кадры камеры — в движок и себе в угол. Опрос, а не обратный вызов: openpnp-capture
     * отдаёт «новый кадр есть» флагом. Ждём полкадра — задержка незаметная, а процессора
     * такой опрос не ест.
     */
    private suspend fun pumpCamera(opened: Camera, source: Long, preview: Frames) {
        val wait = (500L / opened.fps).coerceIn(5L, 50L)
        var shown = 0
        while (currentCoroutineContext().isActive) {
            if (!opened.grab()) {
                delay(wait)
                continue
            }
            runCatching {
                Ffi.request(
                    FfiRequest(
                        capture_video_frame = CaptureVideoFrameRequest(
                            source_handle = source,
                            buffer = VideoBufferInfo(
                                type = VideoBufferType.RGB24,
                                width = opened.width,
                                height = opened.height,
                                data_ptr = Pointer.nativeValue(opened.frame),
                                stride = opened.width * 3,
                            ),
                            timestamp_us = System.nanoTime() / 1000,
                            rotation = VideoRotation.VIDEO_ROTATION_0,
                        ),
                    ),
                )
            }
            // Себе — через кадр: это проверка «я в кадре», а не второй видеопоток.
            if (shown++ % 2 == 0) preview.pictures.value = rgbToPicture(opened)
        }
    }

    // ── Устройства и проверка (настройка «Микрофон и камера») ──────────────

    override var setup: CallSetup = CallSetup()
        set(value) {
            val speakerChanged = value.speaker != field.speaker
            field = value
            // Идёт проверка — сменённые колонки подхватываются сразу, без выхода из
            // настроек (заказчик 2026-09-27). Имя ищется в перечне устройств: Java Sound
            // знает колонки по имени, а выбор хранится идентификатором Windows.
            val check = micCheck
            if (speakerChanged && check != null) {
                // Перечень — из нативной библиотеки, не на потоке экрана: сюда зовут и из него.
                scope.launch(Dispatchers.IO) {
                    check.speakerName = value.speaker?.let { id -> speakers().firstOrNull { it.id == id }?.name }
                    Journal.note(LogCode.CALL_DEVICE, "проверка: колонки сменены на ходу")
                }
            }
        }

    private val _micLevel = MutableStateFlow(0f)
    override val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _preview = MutableStateFlow<VideoHandle?>(null)
    override val preview: StateFlow<VideoHandle?> = _preview.asStateFlow()

    private val _checkTrouble = MutableStateFlow<String?>(null)
    override val checkTrouble: StateFlow<String?> = _checkTrouble.asStateFlow()

    private var checkAudio = 0L
    private var checkSource = 0L
    private var checkTrack = 0L
    private var micCheck: MicCheck? = null
    private var checkApm: CheckApm? = null

    /** Слушать себя в колонках во время проверки. */
    private var listenSelf = false

    override fun listen(on: Boolean) {
        listenSelf = on
        micCheck?.listen = on
        Journal.note(LogCode.CALL_DEVICE, "проверка: слушать себя", "включено" to on)
    }
    private var checkJobs = listOf<Job>()

    /** Устройства звука. ADM их перечисляет только открытым — открываем на миг. */
    private fun audioDevices(): livekit.proto.GetAudioDevicesResponse? {
        if (Ffi.start() != null) return null
        val own = platformAudio.takeIf { it != 0L } ?: checkAudio.takeIf { it != 0L }
        val handle = own ?: runCatching {
            Ffi.request(FfiRequest(new_platform_audio = NewPlatformAudioRequest())).new_platform_audio?.platform_audio?.handle?.id
        }.getOrNull() ?: return null
        try {
            return runCatching {
                Ffi.request(FfiRequest(get_audio_devices = GetAudioDevicesRequest(platform_audio_handle = handle))).get_audio_devices
            }.getOrNull()
        } finally {
            if (own == null) Ffi.drop(handle)
        }
    }

    override fun microphones(): List<CallDevices.Device> =
        audioDevices()?.recording_devices.orEmpty().map { CallDevices.Device(it.guid ?: it.name, it.name) }

    override fun speakers(): List<CallDevices.Device> =
        audioDevices()?.playout_devices.orEmpty().map { CallDevices.Device(it.guid ?: it.name, it.name) }

    override fun cameras(): List<CallDevices.Device> =
        Camera.devices().map { (id, name) -> CallDevices.Device(id, name) }

    /** Поставить ADM выбранные микрофон и колонки. Пропавшее устройство — «по умолчанию». */
    private fun pickDevices(handle: Long) {
        setup.microphone?.let { id ->
            val answer = runCatching {
                Ffi.request(FfiRequest(set_recording_device = SetRecordingDeviceRequest(platform_audio_handle = handle, device_id = id)))
            }.getOrNull()?.set_recording_device
            answer?.error?.let { Journal.trouble(LogCode.CALL_DEVICE, "выбранный микрофон не встал", "причина" to it) }
        }
        setup.speaker?.let { id ->
            val answer = runCatching {
                Ffi.request(FfiRequest(set_playout_device = SetPlayoutDeviceRequest(platform_audio_handle = handle, device_id = id)))
            }.getOrNull()?.set_playout_device
            answer?.error?.let { Journal.trouble(LogCode.CALL_DEVICE, "выбранные колонки не встали", "причина" to it) }
        }
    }

    /**
     * Проверка без звонка: микрофон пишет [MicCheck] (ADM без звонка микрофон не пишет),
     * уровень — по самому звуку, по желанию — в колонки; камера показывает себя.
     *
     * Во время звонка проверки нет: микрофон и камера уже заняты звонком, и второе
     * открытие только помешало бы разговору.
     */
    override suspend fun startCheck() = withContext(worker) {
        closeCheck()
        _checkTrouble.value = null
        if (room != 0L) {
            _checkTrouble.value = "идёт звонок — проверка после него"
            return@withContext
        }
        val jobs = mutableListOf<Job>()
        // Имена выбранных устройств: Java Sound знает их по имени, не по идентификатору.
        val micName = setup.microphone?.let { id -> microphones().firstOrNull { it.id == id }?.name }
        val speakerName = setup.speaker?.let { id -> speakers().firstOrNull { it.id == id }?.name }
        Ffi.start()
        // Обработка — как в звонке: с выбранными эхо-, шумоподавлением и усилением.
        val apm = runCatching { CheckApm(setup, 48_000) }
            .onFailure { Journal.trouble(LogCode.CALL_DEVICE, "проверка: обработка звука не создалась", "причина" to (it.message ?: "?")) }
            .getOrNull()
        checkApm = apm
        val check = runCatching {
            MicCheck(
                micName, speakerName, apm,
                onTrouble = { why -> _checkTrouble.value = why },
                onStats = { raw, processed ->
                    Journal.note(
                        LogCode.CALL_DEVICE, "проверка: уровень речи",
                        "микрофон дБ" to raw, "после обработки дБ" to processed,
                        "усиление" to setup.autoGain, "шумодав" to setup.noiseSuppression,
                    )
                },
            )
        }
            .onFailure {
                _checkTrouble.value = "микрофон не открылся: " + (it.message ?: it::class.simpleName)
                Journal.trouble(LogCode.CALL_DEVICE, "проверка: микрофон не открылся", "причина" to (it.message ?: "?"))
            }
            .getOrNull()
        if (check != null) {
            check.listen = listenSelf
            micCheck = check
            jobs += scope.launch(Dispatchers.IO) {
                // Плавное падение — чтобы полоса не мигала между слогами.
                runCatching { check.run { level -> _micLevel.value = maxOf(level, _micLevel.value * 0.85f) } }
                _micLevel.value = 0f
            }
        }
        // Камера — отдельной задачей: открывается она секундами, и ждать её, чтобы
        // показать полосу микрофона и громкость, незачем.
        val cameraId = setup.camera
        jobs += scope.launch(Dispatchers.IO) {
            when (val opened = Camera.open(640, 480, 15, cameraId)) {
                is Camera.Companion.Opened.Ready -> {
                    val camera = opened.camera
                    try {
                        val frames = Frames()
                        _preview.value = frames
                        val wait = (500L / camera.fps).coerceIn(5L, 50L)
                        while (currentCoroutineContext().isActive) {
                            if (camera.grab()) frames.pictures.value = rgbToPicture(camera) else delay(wait)
                        }
                    } finally {
                        camera.close()
                        _preview.value = null
                    }
                }
                is Camera.Companion.Opened.Failed -> {
                    _preview.value = null
                    if (_checkTrouble.value == null) _checkTrouble.value = opened.why
                }
            }
        }
        checkJobs = jobs
        Journal.note(LogCode.CALL_DEVICE, "проверка устройств начата", "микрофон" to (setup.microphone ?: "по умолчанию"))
    }

    override suspend fun stopCheck() = withContext(worker) { closeCheck() }

    private fun closeCheck() {
        micCheck?.close()
        micCheck = null
        // Обработку закрываем после того, как поток проверки её отпустил.
        val apm = checkApm
        checkApm = null
        checkJobs.forEach { it.cancel() }
        if (apm != null) scope.launch(Dispatchers.IO) { delay(APM_GRACE_MS); apm.close() }
        checkJobs.forEach { it.cancel() }
        checkJobs = emptyList()
        _preview.value = null
        _micLevel.value = 0f
        Ffi.drop(checkTrack)
        Ffi.drop(checkSource)
        Ffi.drop(checkAudio)
        checkTrack = 0L
        checkSource = 0L
        checkAudio = 0L
    }

    override fun micVolume(): Float? = CoreAudio.microphone(setup.microphone)?.let { audio ->
        try {
            audio.volume()
        } finally {
            audio.close()
        }
    }

    override fun setMicVolume(value: Float) {
        CoreAudio.microphone(setup.microphone)?.let { audio ->
            try {
                audio.setVolume(value)
            } finally {
                audio.close()
            }
        }
        Journal.note(LogCode.CALL_DEVICE, "громкость микрофона", "стала" to (value * 100).toInt())
    }

    override fun playTest() {
        val name = setup.speaker?.let { id -> speakers().firstOrNull { it.id == id }?.name }
        SoundTest.play(name)
    }

    private fun stopCamera() {
        cameraJob?.cancel()
        cameraJob = null
        camera?.close()
        camera = null
        _localVideo.value = null
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

/** Наш кодек в кодек протокола. AV1 в нашем перечне нет — решение заказчика. */
private fun VideoCodec.toFfi(): LkVideoCodec = when (this) {
    VideoCodec.H264 -> LkVideoCodec.H264
    VideoCodec.VP9 -> LkVideoCodec.VP9
    VideoCodec.H265 -> LkVideoCodec.H265
    VideoCodec.VP8 -> LkVideoCodec.VP8
}

/** Кадр камеры R,G,B → BGRA для своего окошка. */
internal fun rgbToPicture(camera: Camera): VideoPicture =
    rgbToPicture(camera.width, camera.height, camera.frame.getByteArray(0, camera.width * camera.height * 3))

internal fun rgbToPicture(width: Int, height: Int, rgb: ByteArray): VideoPicture {
    val out = ByteArray(width * height * 4)
    var i = 0
    var o = 0
    while (i < rgb.size) {
        out[o] = rgb[i + 2]
        out[o + 1] = rgb[i + 1]
        out[o + 2] = rgb[i]
        out[o + 3] = -1
        i += 3
        o += 4
    }
    return VideoPicture(width, height, out)
}

/** Обработка звука по выбору человека — для звонка и для проверки одна и та же. */
private fun CallSetup.audioOptions() = AudioSourceOptions(
    echo_cancellation = echoCancellation,
    noise_suppression = noiseSuppression,
    auto_gain_control = autoGain,
)

/** Сколько ждать, пока поток проверки отпустит обработку, прежде чем её закрыть. */
private const val APM_GRACE_MS = 200L
private const val CONNECT_TIMEOUT_MS = 20_000L
private const val DISCONNECT_TIMEOUT_MS = 5_000L

/** Опрос «видео собеседника нет»: два пустых подряд — шесть секунд, как на телефоне. */
private const val LOSS_EVERY_MS = 3_000L
private const val STATS_TIMEOUT_MS = 2_000L
private const val PUBLISH_TIMEOUT_MS = 10_000L
private const val PUBLISH_TRIES = 3
private const val PUBLISH_RETRY_MS = 500L

/**
 * Что ПК кодирует и раскодирует — всё программно (ADR-0031): OpenH264 и libvpx. AV1 в
 * сборке кодируется, но не раскодируется, и в обычный звонок не берётся.
 */
private val PC_ENCODES = setOf(VideoCodec.H264, VideoCodec.VP8, VideoCodec.VP9)
private val PC_DECODES = setOf(VideoCodec.H264, VideoCodec.VP8, VideoCodec.VP9)

/** Как часто пересматриваем кодек под собеседников. */
private const val PEERS_EVERY_MS = 1_000L
