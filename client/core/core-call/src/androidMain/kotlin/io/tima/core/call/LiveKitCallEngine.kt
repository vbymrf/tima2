package io.tima.core.call

import android.content.Context
import android.hardware.camera2.CameraManager
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.AudioOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.BackupVideoCodec
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoCodec as LkVideoCodec
import io.livekit.android.util.flow
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RtpParameters.DegradationPreference
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Звонок на Android — поверх `livekit-android` (К7.2, ПЛАН-СТЕНДА-ЗВОНКОВ С1).
 *
 * **Тонкий слой и должен таким остаться.** Всё, что умеет SFU — выбор слоя, оценка полосы,
 * переподключение, — делает SDK; здесь только перевод его состояний в наши и обратно.
 * Правило записано в [ADR-0006](../../../../../../../../doc/adr/0006-livekit-media-policy.md):
 * Поправка-2 разрешила свои **пресеты публикации** на время испытаний, но не свой
 * клиентский стек.
 *
 * ── ЧЕГО ЗДЕСЬ НЕТ ──────────────────────────────────────────────────────────
 *
 * **Сигналинга.** Кто кому звонит и какая комната — решает наш сервер; сюда приходит
 * готовая [CallDoor]. Смешивать их нельзя: сигналинг общий на все платформы, медиа —
 * платформенное.
 *
 * **Разрешений.** Микрофон и камера спрашиваются там, где есть Activity; движок исходит из
 * того, что разрешение уже дано. Иначе модуль потянул бы за собой UI.
 */
class LiveKitCallEngine(
    private val context: Context,
    private val scope: CoroutineScope,
) : CallEngine {

    private val _state = MutableStateFlow(CallState())
    override val state: StateFlow<CallState> = _state.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoHandle?>(null)
    override val localVideo: StateFlow<VideoHandle?> = _localVideo.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoHandle?>(null)
    override val remoteVideo: StateFlow<VideoHandle?> = _remoteVideo.asStateFlow()

    // ── ГРУППОВОЙ ЗВОНОК (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ3) ─────────────────────────────
    private val _peers = MutableStateFlow<List<CallPeer>>(emptyList())
    override val peers: StateFlow<List<CallPeer>> = _peers.asStateFlow()

    /** Группа идущего звонка; `null` — звонок на двоих. */
    private var group: GroupRoom? = null

    /**
     * Ручки картинок участников — по одной на дорожку, и та же самая, пока дорожка та же:
     * новая ручка на каждый опрос заставила бы экран перебирать поверхность, и картинка
     * замирала бы (та же беда, что `RX9A`, см. [show]).
     */
    private val peerHandles = mutableMapOf<String, LiveKitVideoHandle>()

    /** Потолок высоты своего видео по числу участников; `null` — не урезано. */
    private var ceilingHeight: Int? = null

    /** Пропажа видео у каждого участника группового — для его клетки ([CallPeer.videoLoss]). */
    private val peerLoss = java.util.concurrent.ConcurrentHashMap<String, RemoteVideoLoss>()

    /** Что я принимаю от каждого участника группового — для журнала стенда. */
    private val peerNumbers = java.util.concurrent.ConcurrentHashMap<String, PeerIncoming>()

    /** Что уже сказано о своей публикации атрибутом [BenchAttribute] — шлём только перемену. */
    private var benchSaid = ""

    private var room: Room? = null

    /**
     * Наблюдатели за комнатой — **чтобы их было чем погасить**.
     *
     * Раньше `watch` запускал шесть корутин в область приложения и забывал о них. Область
     * живёт от запуска до запуска, значит наблюдатели прошлых звонков **оставались
     * работать**: два из них опрашивают статистику в вечном цикле, а остальные держат
     * ссылку на давно закрытую комнату. За день разговоров это десятки лишних опросов в
     * секунду — и, что хуже, наблюдатель мёртвой комнаты умеет объявить стадию.
     *
     * Нашлось при работе над перезаходом (С-В5): там комната меняется посреди звонка, и
     * старый наблюдатель объявил бы звонок конченым ровно в тот момент, когда он
     * продолжается.
     */
    private val watchers = mutableListOf<Job>()

    /** Принимаем ли чужое видео. Выключается кнопкой «скрыть» (ЗВ11). */
    private var takeRemote: Boolean = true

    /** Отвечал ли кто-нибудь. Отличает «ещё не ответили» от «собеседник ушёл». */
    private var everAnswered: Boolean = false

    // Прошлый отсчёт байтов и его время: мгновенного битрейта в WebRTC нет, он считается
    // разницей. -1 означает «ещё не считали», и это не то же самое, что ноль.
    private var lastSent: Long = -1
    private var lastReceived: Long = -1
    private var statsAt: Long = 0

    /** Прогон: кодек набора телефону не по силам — см. [CallState.ownVideoUnsent]. */
    private var ownUnsent: String? = null

    /** Переключатели аппаратного кодирования (ПЛАН-ВИДЕО.md В4) — «Настройки → Звонки». */
    @Volatile
    private var settingsCoding = HardwareCoding()

    /** Набор прогона этого звонка — его выбор кодера и раскодировщика; `null` — обычный звонок. */
    @Volatile
    private var runVideo: VideoPreset? = null

    /**
     * Чем кодировать и раскодировать **в этом звонке**: настройки, поверх — выбор прогона
     * стенда (заказчик 2026-09-30, 1а). «Как в настройках» следует за переключателями и
     * посреди звонка. Читают фабрики комнаты — при создании каждого кодера.
     */
    private val coding: HardwareCoding get() = settingsCoding.forRun(runVideo)

    /** Набор этого звонка — чтобы пересмотреть кодек, когда собеседник скажет, что примет. */
    private var publishing: PublishPreset? = null

    /** Кодек, под который сейчас настроена публикация камеры. */
    private var target: VideoCodec? = null

    /**
     * Когда впервые увидели собеседника без атрибута «что принимаю». Ждём его
     * [PeerCodecs.WAIT_MS]; не пришёл — «не знаем», и это VP8 (решение 4).
     */
    private val peerSeen = HashMap<String, Long>()

    /** Что каждый собеседник уже сказал — чтобы писать в журнал только смену. */
    private val peerTold = HashMap<String, String>()

    /** Выбор динамика этого звонка — наш экземпляр, с нашим порядком (ПЛАН-ВИДЕО.md В9). */
    private var sound: AudioSwitchHandler? = null

    /** Человек нажал «Динамик» — дальше звонок его выбор не трогает. */
    private var soundChosen = false

    /** Передняя ли камера — для «Переключение камеры» и для переопубликации. */
    private var cameraFront = true

    /** Сколько камер у телефона. Одна — кнопки «Переключение камеры» нет. */
    private val cameras: Int by lazy {
        runCatching { (context.getSystemService(Context.CAMERA_SERVICE) as CameraManager).cameraIdList.size }.getOrDefault(0)
    }

    /**
     * Прошлые `qpSum` и число кадров по каждой записи дорожки. QP в отчёте WebRTC — сумма
     * за всё время, как и байты; средний QP за последний промежуток — разница суммы на
     * разницу кадров (заказчик 2026-09-27).
     */
    private val lastQp = HashMap<String, Pair<Double, Long>>()

    /** Видео одной записи отчёта: размер, чтобы выбрать верхнюю копию, и счётчики. */
    private class FrameCounts(val id: String, val width: Int, val fps: Double?, val qpSum: Double?, val frames: Long?)

    /** Средний QP с прошлого опроса. `null` — первый опрос, кадров не было или кодер QP не даёт. */
    private fun qpSince(counts: FrameCounts?): Double? {
        val sum = counts?.qpSum ?: return null
        val frames = counts.frames ?: return null
        val was = lastQp.put(counts.id, sum to frames) ?: return null
        val made = frames - was.second
        if (made <= 0) return null
        return (sum - was.first) / made
    }

    override suspend fun connect(door: CallDoor, publish: PublishPreset?) {
        // Пресет применяется ПРИ СОЗДАНИИ комнаты, а не при публикации: кодек и слои
        // участвуют в согласовании, и менять их потом — пересогласование, а иногда разрыв
        // (С-В5 в плане стенда).
        val options = RoomOptions(
            adaptiveStream = publish?.video?.adaptiveStream ?: true,
            dynacast = publish?.video?.dynacast ?: true,
            // Видео — не здесь, а после создания комнаты: кодек выбирается по тому, что
            // умеет кодер телефона, а спросить программную часть WebRTC можно только
            // после того, как её загрузил SDK. Смотри [publishVideoAs].
            // ── ЗВУК ────────────────────────────────────────────────────────────
            //
            // RED — не «улучшение качества», а избыточность: вдвое больше трафика на
            // голос, зато потери до ~20 % не слышны. Включено решением заказчика
            // 2026-09-19; на фоне видео в сотни кбит/с лишние 24 кбит/с незаметны.
            //
            // Отдельной ручки inband FEC у SDK нет — проверено по
            // `AudioTrackPublishDefaults(audioBitrate, dtx, red, preconnect)`. Это ответ
            // на С-В6: RED и DTX задаются, FEC живёт внутри Opus и снаружи не виден.
            audioTrackPublishDefaults = publish?.audio?.let { audio ->
                AudioTrackPublishDefaults(
                    audioBitrate = audio.bitrate,
                    dtx = audio.dtx,
                    red = audio.red,
                )
            },
            videoTrackCaptureDefaults = publish?.video?.let { video ->
                LocalVideoTrackOptions(
                    captureParams = VideoCaptureParameter(
                        width = video.width,
                        height = video.height,
                        maxFps = video.fps,
                    ),
                )
            },
        )
        // Наблюдатели прошлой комнаты гасятся ДО создания новой: работающие поверх новой
        // они удвоили бы каждый опрос и объявили бы стадию по мёртвой комнате.
        stopWatching()
        // Выбор кодера прогона — до фабрик: они читают его при создании первого кодера.
        runVideo = publish?.video
        if (publish != null && (publish.video.encoder != CoderChoice.Settings || publish.video.decoder != CoderChoice.Settings)) {
            Journal.note(
                LogCode.CALL, "кодер и раскодировщик — выбор прогона",
                "набор" to publish.name, "кодер" to publish.video.encoder.wire, "раскодировщик" to publish.video.decoder.wire,
                "кодируем аппаратно" to coding.encode, "раскодируем аппаратно" to coding.decode,
            )
        }
        val created = LiveKit.create(appContext = context, options = options, overrides = overridesFor(publish))
        publishing = publish
        group = door.group
        ceilingHeight = null
        peerHandles.clear()
        _peers.value = emptyList()
        target = null
        // «Скрыть видео» — выбор ЭТОГО звонка. Без сброса он переживал конец звонка: экран
        // следующего показывал «принимаем», а движок не подписывался, и сервер переставал
        // слать видео, которое никто не смотрит (Samsung ← realme, 2026-09-29). На ПК
        // сброс стоял с самого начала.
        takeRemote = true
        peerSeen.clear()
        peerTold.clear()
        soundChosen = false
        cameraFront = true
        publish?.let { publishVideoAs(created, it.video, exact = it.exact) }
        room = created
        everAnswered = false
        watch(created, door.callId)
        _state.value = CallState(
            stage = CallStage.Connecting,
            callId = door.callId,
            ownVideoUnsent = ownUnsent,
            cameraSwitchable = cameras > 1,
            roomPaused = door.group?.paused == true,
        )
        try {
            created.connect(url = door.url, token = door.token)
            // ── МИКРОФОН ВКЛЮЧАЕТСЯ ЗДЕСЬ, И БЕЗ ЭТОГО ЗВОНОК НЕМОЙ ─────────
            //
            // `connect` заводит комнату, но не публикует ничего: что отдавать наверх —
            // решает приложение. Пока этой строки не было, звонок соединялся, таймер
            // шёл, участники видели друг друга — и молчали оба (живой прогон
            // 2026-09-20). Камера не включается: голосовой звонок её не просит, и
            // разрешения на неё в этот момент ещё нет.
            Journal.note(LogCode.CALL, "вошли в комнату", "комната" to door.room)
            announceDecoding(created)
            publishMicrophone(created)
        } catch (e: Throwable) {
            // Причина словами и в состоянии: звонок, который «просто не начался», —
            // худшее из состояний, потому что человек видит пустоту и не знает, чего ждать.
            val why = e.message ?: e::class.simpleName ?: "не подключились"
            Journal.trouble(LogCode.CALL, "в комнату не вошли", "причина" to why)
            _state.value = _state.value.copy(
                stage = CallStage.Ended,
                trouble = why,
            )
        }
    }

    /**
     * Поставить комнате видео пресета — **с кодеком, который телефон действительно умеет.**
     *
     * Без этого шага пресет «H.264» на Honor 8S публиковал молчаливый VP8, а сервер его
     * выбрасывал: видео не было у собеседника, и никто не знал почему. Выбор и его
     * причины — [CodecChoice]; здесь только спросить WebRTC и записать итог в журнал.
     *
     * Пресет пишется в журнал всегда, а не только при замене: «какой кодек ушёл в сеть»
     * — первый вопрос к любому отчёту о пропавшем видео.
     */
    private fun publishVideoAs(room: Room, video: VideoPreset, exact: Boolean) {
        val encodable = PhoneCoders.encodable(coding.encode)
        val choice = CodecChoice.pick(video.codec, video.backup, encodable, exact)
        val can = encodable.joinToString(", ") { it.name }.ifEmpty { "не узнали" }
        val backup = video.backup
        if (backup != null && !backup.backupCapable) {
            // SDK такой запасной не посылает (VideoCodec.backupCapable) — говорим, что его
            // нет, чтобы прогон не числил запасным то, чего в сети не бывает.
            Journal.note(LogCode.CALL, "запасной не годится SDK, его нет", "запасной" to backup.name)
        }
        val unsent = exact && encodable.isNotEmpty() && video.codec !in encodable
        // Событие в окне 0 звонящего (заказчик 2026-09-29): стенд кодек не меняет — и не
        // должен, он показывает слабое место, — но человек видит это сразу, а не в журнале.
        ownUnsent = if (unsent) video.codec.name else null
        if (unsent) {
            // Прогон кодек не меняет, но молчать нельзя: телефон пошлёт VP8 под именем
            // пресета, и сервер видео выбросит. Без этой строки прогон выглядел бы
            // поломкой звонка, а это ответ «телефон этот кодек не умеет».
            Journal.trouble(
                LogCode.CALL, "прогон: кодек пресета телефону не по силам, не меняем",
                "кодек" to video.codec.name, "умеет" to can,
            )
        } else if (choice.substituted) {
            Journal.trouble(
                LogCode.CALL, "кодек пресета телефону не по силам, публикуем другой",
                "просили" to video.codec.name, "умеет" to can, "шлём" to choice.chosen.name,
            )
        } else {
            Journal.note(
                LogCode.CALL, "кодек публикации",
                "шлём" to choice.chosen.name, "запасной" to (choice.backup?.name ?: "нет"), "умеет" to can,
            )
        }
        applyDefaults(room, video, choice.chosen, choice.backup)
    }

    /**
     * Настройки публикации камеры под кодек [codec]. Отдельно от [publishVideoAs]: кодек
     * пересматривается посреди звонка ([watchPeers]), а остальное остаётся тем же.
     */
    private fun applyDefaults(room: Room, video: VideoPreset, codec: VideoCodec, backup: VideoCodec?) {
        target = codec
        val choice = CodecChoice(wanted = video.codec, chosen = codec, backup = backup?.takeIf { it != codec })
        room.videoTrackPublishDefaults = VideoTrackPublishDefaults(
            // Битрейт задаём сами: без него он принадлежал умолчанию SDK, и цена
            // за `MaintainResolution` ложилась на чёткость молча.
            videoEncoding = VideoEncoding(maxBitrate = video.bitrate, maxFps = video.fps),
            videoCodec = choice.chosen.toLiveKit().codecName,
            // ЯВНО, а не умолчанием SDK: у SVC-кодека он молча ставит запасным
            // VP8 с simulcast, и прогон «VP9 SVC» тогда мерит не VP9
            // (ПЛАН-СТЕНДА §5а, «ловушка»).
            simulcast = video.layers == LayerMode.Simulcast,
            // ── РЕЖИМ СЛОЁВ ЗАДАЁМ ВСЕГДА, КОГДА КОДЕК VP9 ─────────────────
            //
            // Для VP9 SDK подставляет `L3T3_KEY`, если режим не задан, — **при любых
            // слоях**. Прогон «VP9 один слой» до 2026-09-25 поэтому шёл тремя слоями и
            // мерил почти то же, что «VP9 SVC». Один слой — это `L1T1`, и сказано явно.
            //
            // Simulcast у VP9 в SDK не бывает вовсе: при заданном режиме он собирает одну
            // SVC-кодировку и `simulcast` не смотрит. Поэтому «VP9 simulcast» — это SVC с
            // режимом из пресета, и режим этот наш, а не умолчание SDK.
            scalabilityMode = when {
                !choice.chosen.svcCapable -> null
                video.layers == LayerMode.Single -> "L1T1"
                else -> video.scalability
            },
            // ── ЗАПАСНОЙ КОДЕК: НАШ, И БЕЗ SIMULCAST ────────────────
            //
            // Умолчание SDK — `BackupVideoCodec(codec = "vp8", simulcast = true)`,
            // и ставится оно молча при публикации SVC-кодека. То есть прогон
            // «VP9 SVC» кодировал бы ещё и три слоя VP8, как только второй
            // телефон попросит запасной, — и померил бы не VP9.
            //
            // `simulcast = false` здесь не забывчивость, а вторая половина того
            // же решения: запасной обязан быть дешевле основного, иначе он не
            // запасной, а вторая публикация.
            //
            // **«Нет запасного» передаётся основным кодеком, а не `null`.** На `null` SDK
            // при VP9 сам ставит запасным VP8 с simulcast. Запасной, равный основному, SDK
            // считает отключённым (`hasBackupCodec` — ложь): серверу он не объявляется, а
            // просьбы сервера отклоняются с «backup codec has been disabled».
            backupCodec = BackupVideoCodec(
                codec = (choice.backup ?: choice.chosen).toLiveKit().codecName,
                simulcast = false,
            ),
            degradationPreference = when (video.degradation) {
                Degradation.MaintainResolution -> DegradationPreference.MAINTAIN_RESOLUTION
                Degradation.MaintainFramerate -> DegradationPreference.MAINTAIN_FRAMERATE
                Degradation.Balanced -> DegradationPreference.BALANCED
            },
        )
    }

    /**
     * Свои фабрики кодеров и раскодировщиков — **всегда**, ради переключателей
     * аппаратного кодирования (ПЛАН-ВИДЕО.md В4): они читаются при создании кодера, и
     * фабрика обязана стоять с самого начала звонка, чтобы смена посреди него подействовала.
     *
     * Связка кодеров — наша в каждом звонке (ПЛАН-ВИДЕО.md В2.1): та, что строит SDK, но
     * аппаратный кодер получает кадр, обрезанный до кратного 16. Снятая галочка стенда
     * «Обрезка до кратного 16» ([VideoPreset.noCrop]) оставляет связку и журнал, но кадр не режет.
     *
     * Фабрикам нужен контекст EGL — тот же, что у комнаты: камера отдаёт кадры текстурами
     * этого контекста, и кодер без него перекладывал бы каждый кадр через память. Поэтому
     * контекст отдаётся SDK вместе с фабриками. Он один на процесс ([sharedEgl]): SDK чужой
     * контекст не освобождает, и новый на каждый звонок копился бы.
     */
    private fun overridesFor(publish: PublishPreset?): LiveKitOverrides {
        val egl = sharedEgl()
        val crop = publish?.video?.noCrop != true
        if (!crop) Journal.note(LogCode.CALL, "кодер: обрезка до кратного 16 выключена набором стенда", "набор" to publish?.name)
        val handler = soundHandler()
        sound = handler
        return LiveKitOverrides(
            videoEncoderFactory = SwitchableEncoderFactory.of(egl.eglBaseContext, crop) { coding.encode },
            videoDecoderFactory = SwitchableDecoderFactory(egl.eglBaseContext) { coding.decode },
            audioOptions = AudioOptions(audioHandler = handler),
            eglBase = egl,
        )
    }

    /**
     * Выбор динамика — тот же, что у SDK, но **с нашим порядком** (ПЛАН-ВИДЕО.md В9,
     * решение заказчика 2026-09-29): наушники, потом разговорный, потом громкая.
     *
     * У SDK громкая стоит выше разговорного, и любой звонок шёл в громкую. Голосовой звонок
     * теперь идёт в разговорный; видеозвонок переводится в громкую, когда включается камера
     * ([autoSound]).
     */
    private fun soundHandler(): AudioSwitchHandler = AudioSwitchHandler(context).apply {
        preferredDeviceList = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Earpiece::class.java,
            AudioDevice.Speakerphone::class.java,
        )
        registerAudioDeviceChangeListener { _, selected -> routed(selected) }
    }

    /** Звук пошёл в [selected] — в состояние и в журнал. */
    private fun routed(selected: AudioDevice?) {
        val route = when (selected) {
            is AudioDevice.Speakerphone -> SoundRoute.Speaker
            is AudioDevice.Earpiece -> SoundRoute.Earpiece
            is AudioDevice.WiredHeadset, is AudioDevice.BluetoothHeadset -> SoundRoute.Headset
            else -> SoundRoute.Unknown
        }
        if (route == _state.value.sound) return
        _state.value = _state.value.copy(sound = route)
        Journal.note(LogCode.CALL, "звук идёт", "куда" to route.name, "устройство" to (selected?.name ?: "—"))
    }

    /**
     * Видеозвонок — в громкую, голосовой — в разговорный (решение заказчика 2026-09-29).
     * Звонок становится видео, когда включают камеру, поэтому решает камера. Человек нажал
     * «Динамик» или подключены наушники — не трогаем.
     */
    private fun autoSound(cameraOn: Boolean) {
        if (soundChosen) return
        val handler = sound ?: return
        val selected = handler.selectedAudioDevice
        if (selected is AudioDevice.WiredHeadset || selected is AudioDevice.BluetoothHeadset) return
        val want = if (cameraOn) AudioDevice.Speakerphone::class.java else AudioDevice.Earpiece::class.java
        if (want.isInstance(selected)) return
        handler.availableAudioDevices.firstOrNull { want.isInstance(it) }?.let { handler.selectDevice(it) }
    }

    override suspend fun setSpeaker(on: Boolean) {
        val handler = sound ?: return
        soundChosen = true
        val available = handler.availableAudioDevices
        val pick = if (on) {
            available.firstOrNull { it is AudioDevice.Speakerphone }
        } else {
            available.firstOrNull { it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }
                ?: available.firstOrNull { it is AudioDevice.Earpiece }
        }
        Journal.note(LogCode.CALL, "кнопка «Динамик»", "громкая" to on, "устройство" to (pick?.name ?: "нет такого"))
        pick?.let { handler.selectDevice(it) }
    }

    override suspend fun remoteFrame(): ByteArray? {
        val track = room?.remoteParticipants?.values
            ?.flatMap { it.videoTrackPublications }
            ?.firstNotNullOfOrNull { it.second as? VideoTrack } ?: return null
        return grabFrame(track)
    }

    override suspend fun switchCamera() {
        cameraFront = !cameraFront
        val position = if (cameraFront) CameraPosition.FRONT else CameraPosition.BACK
        val live = room
        if (live != null) {
            // И в настройки захвата: камеру переопубликовывают (смена кодека), и новая
            // дорожка должна снимать той же камерой, а не передней по умолчанию.
            live.videoTrackCaptureDefaults = live.videoTrackCaptureDefaults.copy(position = position)
            val track = live.localParticipant.videoTrackPublications.firstNotNullOfOrNull { it.second as? LocalVideoTrack }
            if (track != null) attempt { track.switchCamera(position = position) }
        }
        _state.value = _state.value.copy(cameraFront = cameraFront)
        Journal.note(LogCode.CALL, "камера сменена", "стала" to if (cameraFront) "передняя" else "задняя")
    }

    /**
     * Сказать собеседникам, что мы раскодируем (ПЛАН-ВИДЕО.md В5), и записать в журнал, чем
     * телефон вообще умеет кодировать и раскодировать (В1).
     */
    private fun announceDecoding(room: Room) {
        Journal.note(LogCode.CALL, "кодеки телефона", *PhoneCoders.describe().toTypedArray())
        val decodable = PhoneCoders.decodable(coding.decode)
        runCatching { room.localParticipant.updateAttributes(mapOf(PeerCodecs.ATTRIBUTE to PeerCodecs.write(decodable))) }
            .onFailure {
                Journal.trouble(
                    LogCode.CALL, "не сказали собеседнику, что принимаем",
                    "причина" to (it.message ?: it::class.simpleName),
                )
            }
        Journal.note(
            LogCode.CALL,
            "принимаем видео",
            "кодеки" to decodable.joinToString(", ") { it.name },
            "раскодируем аппаратно" to coding.decode,
            "кодируем аппаратно" to coding.encode,
        )
    }

    /**
     * Кодек под собеседников — что они сказали, что примут (ПЛАН-ВИДЕО.md В5). `null` —
     * решать пока нечего: прогон стенда (кодек набора — закон) или собеседник вошёл и ещё
     * не успел сказать.
     */
    private fun codecForPeers(room: Room): VideoCodec? {
        val preset = publishing ?: return null
        if (preset.exact) return null
        val encodable = PhoneCoders.encodable(coding.encode)
        val now = nowMs()
        val peers = mutableListOf<Set<VideoCodec>?>()
        for ((identity, who) in room.remoteParticipants) {
            val id = identity.value
            val raw = who.attributes[PeerCodecs.ATTRIBUTE]
            val told = PeerCodecs.read(raw)
            if (told != null) {
                if (peerTold[id] != raw) {
                    peerTold[id] = raw.orEmpty()
                    Journal.note(LogCode.CALL, "собеседник принимает", "кодеки" to told.joinToString(", ") { it.name })
                }
                peers += told
                continue
            }
            val seen = peerSeen.getOrPut(id) { now }
            if (now - seen < PeerCodecs.WAIT_MS) return null
            if (peerTold[id] != UNKNOWN) {
                peerTold[id] = UNKNOWN
                Journal.trouble(
                    LogCode.CALL, "собеседник не сказал, что принимает — шлём VP8",
                    "ждали мс" to PeerCodecs.WAIT_MS,
                )
            }
            peers += null
        }
        return PeerCodecs.choose(preset.video.codec, encodable, peers)
    }

    /**
     * Пересматривать кодек, пока идёт звонок: собеседник вошёл, сказал, что примет, сменил
     * это переключателем или не сказал ничего за [PeerCodecs.WAIT_MS]. Раз в секунду —
     * дёшево, и «не сказал за три секунды» не требует отдельного таймера.
     */
    private fun watchPeers(room: Room) {
        watchers += scope.launch {
            while (isActive) {
                delay(PEERS_EVERY_MS)
                // Собеседник свернул приложение — его видео на паузе (1б, 2026-10-01).
                val paused = room.remoteParticipants.values.any { it.attributes[VideoPause.ATTRIBUTE] == VideoPause.PAUSED }
                if (paused != _state.value.peerPaused) {
                    _state.value = _state.value.copy(peerPaused = paused)
                    Journal.note(LogCode.CALL, "собеседник свернул приложение — его видео на паузе", "на паузе" to paused)
                }
                val preset = publishing ?: continue
                val codec = codecForPeers(room) ?: continue
                if (codec == target) continue
                Journal.note(
                    LogCode.CALL,
                    "кодек сменён под собеседника",
                    "был" to (target?.name ?: "—"),
                    "стал" to codec.name,
                )
                applyDefaults(room, preset.video, codec, null)
                republishCamera(room, "кодек под собеседника")
            }
        }
    }

    /**
     * Опубликовать камеру заново — **с новым кодеком или новым кодером**. Снять
     * публикацию и включить камеру снова — единственный путь: кодек и кодер выбираются при
     * публикации, у живой их не сменить. Разговор при этом не прерывается, картинка у
     * собеседника замирает на секунду.
     *
     * Камера выключена — снимаем приглушённую публикацию: иначе включение вернуло бы её со
     * старым кодеком.
     */
    private suspend fun republishCamera(room: Room, why: String) {
        val publication = room.localParticipant.videoTrackPublications.firstOrNull() ?: return
        val track = publication.second ?: return
        val live = !publication.first.muted
        attempt {
            room.localParticipant.unpublishTrack(track, true)
            if (live) room.localParticipant.setCameraEnabled(true)
        }
        Journal.note(LogCode.CALL, "камера переопубликована", "почему" to why, "камера включена" to live)
    }

    override suspend fun setHardwareCoding(settings: HardwareCoding) {
        val was = coding
        settingsCoding = settings
        // Выбор прогона сильнее настроек: переключатель посреди прогона с «аппаратным» или
        // «программным» ничего не меняет, и переопубликовывать нечего.
        val coding = coding
        if (was == coding) return
        Journal.note(
            LogCode.CALL, "аппаратное кодирование",
            "кодирование" to coding.encode, "раскодирование" to coding.decode,
        )
        val live = room ?: return
        if (was.encode != coding.encode) {
            // Кодер берётся из фабрики при публикации — значит переопубликовать, даже если
            // кодек тот же. Без аппаратного H.264 нет вовсе — кодек пересмотрится.
            publishing?.let { preset ->
                val encodable = PhoneCoders.encodable(coding.encode)
                val codec = codecForPeers(live) ?: CodecChoice.pick(preset.video.codec, null, encodable, preset.exact).chosen
                applyDefaults(live, preset.video, codec, if (preset.exact) preset.video.backup else null)
            }
            republishCamera(live, "аппаратное кодирование " + if (coding.encode) "включено" else "выключено")
        }
        if (was.decode != coding.decode) {
            // Раскодировщик создаётся при подписке — переподписаться. И сказать
            // собеседнику новое «что принимаю»: без аппаратного может не быть H.264.
            announceDecoding(live)
            if (takeRemote) resubscribe(live)
        }
    }

    /**
     * Отписаться от видео собеседника и подписаться снова — чтобы раскодировщик создался
     * заново из фабрики с новым переключателем. Мимо [setRemoteVideo]: та меняет
     * состояние «скрыто нами», и в ленте окна 0 мелькнуло бы событие, которого не было.
     */
    private suspend fun resubscribe(room: Room) {
        fun subscribe(on: Boolean) {
            for (who in room.remoteParticipants.values) {
                for (publication in who.videoTrackPublications) {
                    (publication.first as? RemoteTrackPublication)?.setSubscribed(on)
                }
            }
        }
        attempt { subscribe(false) }
        delay(RESUBSCRIBE_PAUSE_MS)
        attempt { subscribe(true) }
        Journal.note(LogCode.CALL, "видео собеседника переподписано — новый раскодировщик")
    }

    override suspend fun reenter(door: CallDoor, publish: PublishPreset?) {
        // Не через `disconnect()`: он объявляет звонок конченым, а звонок продолжается.
        // Здесь кончается только комната, и человек об этом знает — он сам нажал.
        stopWatching()
        room?.disconnect()
        room = null
        _localVideo.value = null
        _remoteVideo.value = null
        // Камеру возвращаем такой же, какой она была: набор меняют, чтобы сравнить
        // картинку, и вернуться в комнату с выключенной камерой значило бы сравнивать
        // не то.
        val camera = _state.value.cameraOn
        _state.value = _state.value.copy(stage = CallStage.Connecting)
        connect(door, publish)
        if (camera) {
            // Та же гонка, что у микрофона: публикующее соединение собирается не
            // мгновенно, а камера — вторая дорожка подряд.
            delay(PUBLISH_RETRY_MS)
            setCamera(true)
        }
    }

    /**
     * Поднять микрофон — **отдельно от входа в комнату и с повтором**.
     *
     * ── ПОЧЕМУ ОТДЕЛЬНО ────────────────────────────────────────────────────
     *
     * Раньше это стояло внутри той же `try`, что и вход, и отказ микрофона печатался как
     * «в комнату не вошли» — хотя вошли. Разбор при этом уходил в сеть и в токены, а
     * беда была в дорожке.
     *
     * ── ПОЧЕМУ С ПОВТОРОМ ──────────────────────────────────────────────────
     *
     * `publisher is not configured yet!` — SDK ещё не собрал публикующее соединение.
     * Ловится на перезаходе (живой прогон 2026-09-21): комната открывается второй раз
     * подряд, и дорожка просится раньше, чем есть куда. Через полсекунды всё готово.
     *
     * **Запись в журнал именно об этом шаге.** Немой звонок 2026-09-20 был невидим в
     * отчёте ровно потому, что журнал знал «звонок начат» и «звонок закончен», а
     * поднялась ли дорожка — не знал никто.
     */
    private suspend fun publishMicrophone(room: Room) {
        var last: Throwable? = null
        repeat(PUBLISH_TRIES) { attemptNo ->
            try {
                room.localParticipant.setMicrophoneEnabled(true)
                _state.value = _state.value.copy(microphoneOn = true)
                Journal.note(LogCode.CALL, "микрофон опубликован", "попытка" to attemptNo + 1)
                return
            } catch (e: Throwable) {
                last = e
                delay(PUBLISH_RETRY_MS)
            }
        }
        val why = last?.message ?: last?.let { it::class.simpleName } ?: "дорожка не поднялась"
        Journal.trouble(LogCode.CALL, "микрофон не опубликован", "причина" to why)
        _state.value = _state.value.copy(notice = why)
    }

    /** Погасить наблюдателей. Идемпотентно: гасить нечего — значит ничего. */
    private fun stopWatching() {
        for (job in watchers) job.cancel()
        watchers.clear()
    }

    override suspend fun disconnect() {
        stopWatching()
        room?.disconnect()
        room = null
        // Дорожки гасим сами: после disconnect их некому отдать, а оставленные они
        // держали бы поверхность, которой больше некуда рисовать.
        _localVideo.value = null
        _remoteVideo.value = null
        _peers.value = emptyList()
        peerHandles.clear()
        peerLoss.clear()
        peerNumbers.clear()
        benchSaid = ""
        _state.value = _state.value.copy(stage = CallStage.Ended)
    }

    override suspend fun setRemoteVideo(on: Boolean) {
        takeRemote = on
        val live = room ?: return
        attempt {
            for (who in live.remoteParticipants.values) {
                for (publication in who.videoTrackPublications) {
                    // Отписка бывает только у чужой дорожки — своя публикуется, а не
                    // принимается. Приведение и есть эта проверка.
                    (publication.first as? RemoteTrackPublication)?.setSubscribed(on)
                }
            }
        }
        _state.value = _state.value.copy(remoteVideoTaken = on)
        // Наблюдатель один и уже стоит — второй означал бы вторую подписку на каждую
        // дорожку и удвоение при каждом нажатии «скрыть · показать».
        pickRemote(live)
    }

    override suspend fun setMicrophone(on: Boolean) {
        val done = attempt { room?.localParticipant?.setMicrophoneEnabled(on) }
        // Состояние меняется, только если дорожка действительно поднялась. Иначе экран
        // опять начал бы показывать то, чего нет, — беда, с которой это всё началось.
        if (done) _state.value = _state.value.copy(microphoneOn = on)
    }

    override suspend fun setCamera(on: Boolean) {
        val done = attempt { room?.localParticipant?.setCameraEnabled(on) }
        if (done) _state.value = _state.value.copy(cameraOn = on)
    }

    override suspend fun cameraFrames(): Long? {
        val track = room?.localParticipant?.videoTrackPublications?.firstOrNull()?.second ?: return null
        val report = runCatching { track.getRTCStats() }.getOrNull() ?: return null
        return report.statsMap.values
            .filter { it.type == "outbound-rtp" && it.members["kind"] == "video" }
            .sumOf { (it.members["framesEncoded"] as? Number)?.toLong() ?: 0L }
    }

    /**
     * Камеру открыть заново (заказчик 2026-10-01). HyperOS отбирает её у свёрнутого
     * приложения через несколько секунд, и ни WebRTC, ни LiveKit этого не замечают: дорожка
     * опубликована, кадров нет — `БЕДЫ/2026-10-01-камера-в-фоне.md`.
     */
    override suspend fun restartCamera() {
        val track = room?.localParticipant?.videoTrackPublications?.firstOrNull()?.second as? LocalVideoTrack ?: return
        val done = attempt { track.restartTrack() }
        Journal.note(LogCode.CALL, "камера открыта заново", "удалось" to done)
    }

    override suspend fun announcePaused(paused: Boolean) {
        val live = room ?: return
        runCatching { live.localParticipant.updateAttributes(mapOf(VideoPause.ATTRIBUTE to VideoPause.value(paused))) }
            .onFailure { Journal.trouble(LogCode.CALL, "не сказали собеседнику про паузу видео", "причина" to (it.message ?: it::class.simpleName)) }
    }

    /**
     * Числа для стенда — С4 в ПЛАН-СТЕНДА-ЗВОНКОВ §3.2.
     *
     * ── ПОЧЕМУ БИТРЕЙТ СЧИТАЕТСЯ ЗДЕСЬ, А НЕ БЕРЁТСЯ ГОТОВЫМ ────────────────
     *
     * Мгновенного битрейта в WebRTC нет: отчёт отдаёт **накопленные** байты. Среднее за
     * звонок при этом бесполезно — оно размазывает разгон полосы по всей минуте и прячет
     * провал ровно там, где он интересен. Поэтому держим прошлый отсчёт и делим разницу
     * на прошедшее время; первый вызов честно отдаёт нули — сравнивать не с чем.
     *
     * ── И ПОЧЕМУ КОДЕР НАЗВАН ИМЕНЕМ ────────────────────────────────────────
     *
     * `encoderImplementation` решает спор, который иначе решается рассуждением:
     * аппаратный `OMX.sprd.h264.encoder` у realme или программный `libvpx`. Без этой
     * строки числа прогонов нечитаемы — разница между H.264 на Samsung и на Redmi может
     * оказаться разницей двух реализаций, а не настроек (ПЛАН-СТЕНДА §5а).
     */
    override suspend fun stats(): CallStats? {
        val live = room ?: return null
        val now = nowMs()
        val seconds = if (statsAt == 0L) 0.0 else (now - statsAt) / 1000.0
        statsAt = now

        var sent = 0L
        var received = 0L
        var lost = 0L
        var rtt: Int? = null
        var encoder: String? = null
        // ── КОДЕК БЕРЁТСЯ ПО ССЫЛКЕ, А НЕ ПОСЛЕДНИЙ ПОПАВШИЙСЯ ──────────────
        //
        // Записей типа `codec` в отчёте столько, сколько кодеков согласовано, — все
        // пять, а не один используемый. Раньше брался последний по перебору, и один и
        // тот же набор H.264 давал в отчётах то H264, то VP9, то H265 (живой прогон
        // 2026-09-21). Числа с такой строкой нечитаемы: сравнивать их не с чем.
        //
        // Верный путь один: у исходящей дорожки есть `codecId`, и он указывает ровно на
        // ту запись, которая описывает работающий кодек.
        val codecs = HashMap<String, String>()
        var videoCodecId: String? = null
        var downCodecId: String? = null
        // Ширина — чтобы сложить копии от меньшей к большей; строка — то, что покажем.
        val upFrames = mutableListOf<Pair<Int, String>>()
        var downFrame: Pair<Int, String>? = null
        val upVideo = mutableListOf<FrameCounts>()
        val downVideo = mutableListOf<FrameCounts>()
        var decoder: String? = null
        var freezes: Long? = null
        var dropped: Long? = null
        var decodeMs: Double? = null

        val ours = live.localParticipant.trackPublications.values
            .mapNotNull { it.track as? io.livekit.android.room.track.Track }
        val theirs = live.remoteParticipants.values
            .flatMap { who -> who.trackPublications.values }
            .mapNotNull { it.track as? io.livekit.android.room.track.Track }

        for (track in ours + theirs) {
            val report = runCatching { track.getRTCStats() }.getOrNull() ?: continue
            for (entry in report.statsMap.values) {
                when (entry.type) {
                    "outbound-rtp" -> {
                        sent += (entry.members["bytesSent"] as? Number)?.toLong() ?: 0L
                        if (entry.members["kind"] == "video") {
                            encoder = entry.members["encoderImplementation"]?.toString() ?: encoder
                            videoCodecId = entry.members["codecId"]?.toString() ?: videoCodecId
                            // Копия, погашенная Dynacast (`active = false`), не кодируется —
                            // её размер был бы прошлым, а не нынешним.
                            val w = (entry.members["frameWidth"] as? Number)?.toInt()
                            val h = (entry.members["frameHeight"] as? Number)?.toInt()
                            if (w != null && h != null && entry.members["active"] != false) {
                                val mode = entry.members["scalabilityMode"]?.toString()
                                    ?.takeIf { it.isNotBlank() && it != "L1T1" }
                                upFrames += w to ("" + w + "×" + h + (mode?.let { " $it" } ?: ""))
                                upVideo += FrameCounts(
                                    id = entry.id,
                                    width = w,
                                    fps = (entry.members["framesPerSecond"] as? Number)?.toDouble(),
                                    qpSum = (entry.members["qpSum"] as? Number)?.toDouble(),
                                    frames = (entry.members["framesEncoded"] as? Number)?.toLong(),
                                )
                            }
                        }
                    }

                    "inbound-rtp" -> {
                        received += (entry.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                        lost += (entry.members["packetsLost"] as? Number)?.toLong() ?: 0L
                        val w = (entry.members["frameWidth"] as? Number)?.toInt()
                        val h = (entry.members["frameHeight"] as? Number)?.toInt()
                        if (entry.members["kind"] == "video" && w != null && h != null) {
                            if (downFrame == null || w > downFrame!!.first) downFrame = w to ("" + w + "×" + h)
                            downVideo += FrameCounts(
                                id = entry.id,
                                width = w,
                                fps = (entry.members["framesPerSecond"] as? Number)?.toDouble(),
                                qpSum = (entry.members["qpSum"] as? Number)?.toDouble(),
                                frames = (entry.members["framesDecoded"] as? Number)?.toLong(),
                            )
                            // Раскодировщик и его беды (ПЛАН-ВИДЕО.md В1).
                            decoder = entry.members["decoderImplementation"]?.toString() ?: decoder
                            // Кодек пришедшего — окно 0 «Кодек вниз» (заказчик 2026-09-30, 2а).
                            downCodecId = entry.members["codecId"]?.toString() ?: downCodecId
                            freezes = (entry.members["freezeCount"] as? Number)?.toLong() ?: freezes
                            dropped = (entry.members["framesDropped"] as? Number)?.toLong() ?: dropped
                            val decoded = (entry.members["framesDecoded"] as? Number)?.toLong()
                            val spent = (entry.members["totalDecodeTime"] as? Number)?.toDouble()
                            if (decoded != null && decoded > 0 && spent != null) decodeMs = spent * 1000 / decoded
                        }
                    }

                    // Складываем все — какая из них наша, скажет codecId выше.
                    "codec" -> {
                        val mime = entry.members["mimeType"]?.toString()
                        if (mime != null) codecs[entry.id] = mime
                    }

                    // Пара кандидатов — единственное место, где WebRTC говорит про RTT
                    // всего соединения, а не отдельной дорожки.
                    "candidate-pair" -> {
                        val chosen = entry.members["nominated"] == true
                        val trip = (entry.members["currentRoundTripTime"] as? Number)?.toDouble()
                        if (chosen && trip != null) rtt = (trip * 1000).toInt()
                    }
                }
            }
        }

        val up = if (seconds > 0 && lastSent >= 0) ((sent - lastSent) * 8 / seconds).toLong() else 0L
        val down = if (seconds > 0 && lastReceived >= 0) ((received - lastReceived) * 8 / seconds).toLong() else 0L
        lastSent = sent
        lastReceived = received

        return CallStats(
            upBitrate = up.coerceAtLeast(0),
            downBitrate = down.coerceAtLeast(0),
            rttMs = rtt,
            packetsLost = lost,
            videoCodec = videoCodecId?.let { codecs[it] }?.removePrefix("video/"),
            hardwareEncoder = encoder?.let { hardware(it) },
            upFrames = upFrames.sortedBy { it.first }.map { it.second },
            downFrame = downFrame?.second,
            // Верхняя копия: у simulcast нижние кодируются отдельно, и их частота с QP —
            // про другое качество, а смотрят на то, что видно крупно.
            upFps = upVideo.maxByOrNull { it.width }?.fps,
            upQp = qpSince(upVideo.maxByOrNull { it.width }),
            downFps = downVideo.maxByOrNull { it.width }?.fps,
            downQp = qpSince(downVideo.maxByOrNull { it.width }),
            downDecoder = decoder,
            downCodec = downCodecId?.let { codecs[it] }?.removePrefix("video/"),
            hardwareDecoder = decoder?.let { hardware(it) },
            downFreezes = freezes,
            downDropped = dropped,
            downDecodeMs = decodeMs,
        )
    }

    /**
     * Позвать SDK и **пережить отказ**.
     *
     * 2026-09-20: нажатие на камеру в голосовом звонке роняло приложение целиком —
     * `Camera permissions are required to create a camera video track` летело из
     * корутины, где его никто не ждал (отчёт `RVUU`). Разрешение с тех пор спрашивается
     * заранее, но ловушка нужна независимо от этой причины: у медиа отказов много —
     * камеру занял другой процесс, кодер не поднялся, дорожку отверг SFU, — и ни один
     * из них не повод закрывать приложение посреди разговора.
     */
    private suspend fun attempt(what: suspend () -> Unit): Boolean = try {
        what()
        true
    } catch (e: Throwable) {
        val why = e.message ?: e::class.simpleName ?: "движок отказал"
        Journal.trouble(LogCode.CALL, "движок не принял команду", "причина" to why)
        _state.value = _state.value.copy(notice = why)
        false
    }

    /**
     * Состояние комнаты потоком. У SDK оно приходит делегатом `flowDelegate`, и подписка
     * живёт столько, сколько живёт [scope] — то есть столько, сколько открыт звонок.
     */
    private fun watch(room: Room, callId: String) {
        watchers += scope.launch {
            room::state.flow.collect { settle(room, callId) }
        }
        watchers += scope.launch {
            room::remoteParticipants.flow.collect { settle(room, callId) }
        }
        watchLocalVideo(room)
        watchRemoteVideo(room)
        watchOutgoing(room)
        // Групповой — по каждому участнику (заказчик 2026-10-01): общий наблюдатель смотрел
        // на одну случайную дорожку, и в журнале был виден только один из участников.
        if (group != null) {
            watchGroupIncoming(room)
        } else {
            watchIncoming(room)
            watchRemoteLoss(room)
        }
        watchPeers(room)
        watchQuality(room)
        watchBreaks(room)
        if (group != null) watchGroup(room)
    }

    /**
     * Оценка связи — словом SFU, а не нашим счётом (§3.4).
     *
     * **До 2026-09-20 поле `quality` не заполнялось ничем.** Экран его рисовал, значение
     * всегда было `Unknown`, и выглядело это как «связь неизвестна» на идеальном канале.
     * Своей оценки мы не считаем принципиально: у SFU есть пороги (`scorer.go`: 80 / 40 /
     * 20) и вся статистика обеих сторон, а у нас — половина.
     */
    private fun watchQuality(room: Room) {
        watchers += scope.launch {
            room.localParticipant::connectionQuality.flow.collect { quality ->
                val ours = when (quality) {
                    ConnectionQuality.EXCELLENT -> CallQuality.Excellent
                    ConnectionQuality.GOOD -> CallQuality.Good
                    ConnectionQuality.POOR -> CallQuality.Poor
                    ConnectionQuality.LOST -> CallQuality.Lost
                    else -> CallQuality.Unknown
                }
                if (ours == _state.value.quality) return@collect
                _state.value = _state.value.copy(quality = ours)
                Journal.note(LogCode.CALL, "оценка связи от SFU", "стала" to ours.name)
            }
        }
    }

    /**
     * Обрывы: сколько заняло возвращение (§3.4).
     *
     * ── ПОЧЕМУ ВАЖНА ИМЕННО ДЛИТЕЛЬНОСТЬ ────────────────────────────────────
     *
     * «Связь пропадала» — не сведения: она пропадает у всех и всегда. Сведения — сколько
     * её не было. Полсекунды человек не заметит, пятнадцать секунд он положит трубку, а
     * в журнале оба случая до сих пор выглядели одинаково: две строки о смене стадии.
     *
     * Предел возврата — тридцать секунд (`departure_timeout` в нашей конфигурации SFU):
     * дольше — и участника считают ушедшим насовсем.
     */
    private fun watchBreaks(room: Room) {
        watchers += scope.launch {
            var broke = 0L
            room::state.flow.collect { state ->
                when (state) {
                    Room.State.RECONNECTING -> if (broke == 0L) {
                        broke = nowMs()
                        Journal.trouble(LogCode.CALL, "связь потеряна, возвращаемся")
                    }

                    Room.State.CONNECTED -> if (broke != 0L) {
                        val took = (nowMs() - broke) / 1000.0
                        broke = 0L
                        Journal.note(LogCode.CALL, "связь вернулась", "заняло с" to took.toString())
                    }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Что приходит сверху: размер чужого кадра и **смена слоя** (§3.4).
     *
     * ── ПОЧЕМУ СЛОЙ УЗНАЁТСЯ ПО РАЗМЕРУ КАДРА ───────────────────────────────
     *
     * Номера слоя в статистике приёмника нет: подписчик получает поток, а какой из
     * трёх ему отдал SFU — знает SFU. Зато видно разрешение, а слои тем и отличаются.
     * Это **признак, а не номер**, и в отчёте его надо называть именно так; зато он не
     * требует ни доработки SDK, ни доверия к чужому полю.
     *
     * Пишем только смену: размер за звонок меняется единицы раз, а опрос идёт каждые три
     * секунды.
     */
    /**
     * Видео собеседника нет, хотя он его показывает, — и почему ([RemoteVideoWatch],
     * заказчик 2026-09-29).
     *
     * Honor, объявивший H.264 и пославший VP8: у Redmi не было ни строки «видео собеседника
     * идёт» — человек видел чёрное и не знал почему. Теперь это событие в ленте окна 0.
     */
    private fun watchRemoteLoss(room: Room) {
        watchers += scope.launch {
            val watch = RemoteVideoWatch()
            var told: RemoteVideoLoss? = null
            while (isActive) {
                delay(STATS_EVERY_MS)
                val publications = room.remoteParticipants.values.flatMap { it.videoTrackPublications }
                val published = publications.any { !it.first.muted }
                val track = publications.firstNotNullOfOrNull { it.second as? VideoTrack }
                val report = track?.let { runCatching { it.getRTCStats()?.statsMap?.values }.getOrNull() }
                val incoming = report?.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
                val codec = incoming?.members?.get("codecId")?.toString()?.let { id ->
                    report.firstOrNull { it.id == id }?.members?.get("mimeType")?.toString()?.removePrefix("video/")
                }
                val now = _state.value
                val loss = watch.next(
                    RemoteVideoWatch.Poll(
                        published = published,
                        // Скрыто нами или погашено сервером — у этого свои события.
                        excused = !now.remoteVideoTaken || now.videoPaused || now.stage != CallStage.Connected,
                        bytes = (incoming?.members?.get("bytesReceived") as? Number)?.toLong(),
                        frames = (incoming?.members?.get("framesDecoded") as? Number)?.toLong(),
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

    /** Что знаем о входящем видео одного участника между опросами. */
    private class PeerWatch {
        var said = ""
        var got = -1L
        var froze = 0L
        var misaligned = ""
        val watch = RemoteVideoWatch()
        var told: RemoteVideoLoss? = null
    }

    /**
     * Входящее видео группового звонка — **по каждому участнику** (заказчик 2026-10-01).
     *
     * Те же строки, что у звонка на двоих, и каждая называет участника (`кто=` — первые
     * восемь знаков его номера): кадр, кодек, полоса, раскодировщик, время на кадр,
     * выброшенные, замирания, некратный кадр, пропажа видео. Строка пишется на смену, а не
     * на каждый опрос: участников до 25, и лента из одинаковых строк ничего не объясняет.
     */
    private fun watchGroupIncoming(room: Room) {
        watchers += scope.launch {
            val known = HashMap<String, PeerWatch>()
            while (isActive) {
                delay(STATS_EVERY_MS)
                val present = HashSet<String>()
                for (who in room.remoteParticipants.values) {
                    val identity = who.identity?.value ?: continue
                    present += identity
                    val me = known.getOrPut(identity) { PeerWatch() }
                    val kto = userOfIdentity(identity).take(8)
                    val camera = who.videoTrackPublications.firstOrNull { (pub, _) -> pub.source == Track.Source.CAMERA }
                    val published = camera != null && !camera.first.muted
                    val track = camera?.second as? VideoTrack
                    val report = track?.let { runCatching { it.getRTCStats()?.statsMap?.values }.getOrNull() }
                    val incoming = report?.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
                    val codec = incoming?.members?.get("codecId")?.toString()?.let { id ->
                        report.firstOrNull { it.id == id }?.members?.get("mimeType")?.toString()?.removePrefix("video/")
                    }

                    // Пропажа видео — своя у участника, на его клетку.
                    val loss = me.watch.next(
                        RemoteVideoWatch.Poll(
                            published = published,
                            excused = !takeRemote || _state.value.roomPaused || _state.value.stage != CallStage.Connected,
                            bytes = (incoming?.members?.get("bytesReceived") as? Number)?.toLong(),
                            frames = (incoming?.members?.get("framesDecoded") as? Number)?.toLong(),
                            codec = codec,
                        ),
                    )
                    if (loss != me.told) {
                        me.told = loss
                        if (loss == null) peerLoss.remove(identity) else peerLoss[identity] = loss
                        when (loss) {
                            null -> Journal.note(LogCode.CALL, "видео участника снова показывается", "кто" to kto)
                            RemoteVideoLoss.NotArriving -> Journal.trouble(LogCode.CALL, "видео участника не приходит", "кто" to kto)
                            is RemoteVideoLoss.NotDecoding -> Journal.trouble(LogCode.CALL, "видео участника не раскодируется", "кто" to kto, "кодек" to loss.codec)
                        }
                    }
                    incoming ?: continue

                    val w = incoming.members["frameWidth"] ?: continue
                    val h = incoming.members["frameHeight"] ?: continue
                    val bytes = (incoming.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                    val kbit = if (me.got < 0) -1L else (bytes - me.got) * 8 / (STATS_EVERY_MS / 1000) / 1000
                    me.got = bytes
                    val decoder = incoming.members["decoderImplementation"]?.toString() ?: "—"
                    val decoded = (incoming.members["framesDecoded"] as? Number)?.toLong()
                    val decodeSeconds = (incoming.members["totalDecodeTime"] as? Number)?.toDouble()
                    val decodeMs = if (decoded != null && decoded > 0 && decodeSeconds != null) decodeSeconds * 1000 / decoded else null
                    val dropped = (incoming.members["framesDropped"] as? Number)?.toLong()

                    val freezes = (incoming.members["freezeCount"] as? Number)?.toLong() ?: 0L
                    if (freezes > me.froze) {
                        me.froze = freezes
                        Journal.trouble(
                            LogCode.CALL, "видео участника замирало", "кто" to kto, "раз" to freezes,
                            "всего с" to ((incoming.members["totalFreezesDuration"] as? Number)?.toDouble()?.let { tenth(it) } ?: "—"),
                        )
                    }
                    val inSize = "" + w + "×" + h
                    val inW = (w as? Number)?.toInt()
                    val inH = (h as? Number)?.toInt()
                    if (inW != null && inH != null && !CenterCrop.aligned(inW, inH) && inSize != me.misaligned) {
                        Journal.trouble(LogCode.CALL, "пришло некратное", "кто" to kto, "кадр" to inSize, "раскодировщик" to decoder)
                    }
                    me.misaligned = inSize

                    peerNumbers[identity] = PeerIncoming(
                        frame = inSize, codec = codec, kbit = kbit.takeIf { it >= 0 }, decoder = decoder,
                        decodeMs = decodeMs, dropped = dropped, freezes = freezes,
                    )
                    val line = inSize + "|" + (kbit / 100) + "|" + decoder + "|" + codec
                    if (line == me.said) continue
                    me.said = line
                    Journal.note(
                        LogCode.CALL, "приходящее видео участника",
                        "кто" to kto,
                        "кадр" to inSize,
                        "кодек" to (codec ?: "—"),
                        "кбит/с" to if (kbit < 0) "считаем" else kbit.toString(),
                        "раскодировщик" to decoder,
                        "раскод мс" to (decodeMs?.let { tenth(it) } ?: "—"),
                        "выброшено" to (dropped ?: "—"),
                    )
                }
                // Ушедшие — забыть: вернётся, начнём заново.
                known.keys.retainAll(present)
                peerLoss.keys.retainAll(present)
                peerNumbers.keys.retainAll(present)
            }
        }
    }

    private fun watchIncoming(room: Room) {
        watchers += scope.launch {
            var said = ""
            var got = -1L
            var froze = 0L
            var misaligned = ""
            while (isActive) {
                delay(STATS_EVERY_MS)
                val track = room.remoteParticipants.values
                    .flatMap { it.videoTrackPublications }
                    .firstNotNullOfOrNull { it.second as? VideoTrack } ?: continue
                val incoming = runCatching {
                    track.getRTCStats()?.statsMap?.values
                        ?.firstOrNull { it.type == "inbound-rtp" && it.members["kind"] == "video" }
                }.getOrNull() ?: continue

                val w = incoming.members["frameWidth"] ?: continue
                val h = incoming.members["frameHeight"] ?: continue
                val bytes = (incoming.members["bytesReceived"] as? Number)?.toLong() ?: 0L
                val kbit = if (got < 0) -1L else (bytes - got) * 8 / (STATS_EVERY_MS / 1000) / 1000
                got = bytes

                // ── ЧЕМ РАСКОДИРУЕТСЯ (ПЛАН-ВИДЕО.md В1) ─────────────────────
                //
                // Полосы VP9 у Redmi 2026-09-25 нельзя было отнести ни к кодеру
                // собеседника, ни к своему раскодировщику: имени последнего журнал не знал.
                // Время раскодирования кадра и выброшенные кадры — признаки того, что
                // раскодировщик не успевает.
                val decoder = incoming.members["decoderImplementation"]?.toString() ?: "—"
                val decoded = (incoming.members["framesDecoded"] as? Number)?.toLong()
                val decodeSeconds = (incoming.members["totalDecodeTime"] as? Number)?.toDouble()
                val decodeMs = if (decoded != null && decoded > 0 && decodeSeconds != null) decodeSeconds * 1000 / decoded else null
                val dropped = (incoming.members["framesDropped"] as? Number)?.toLong()

                // Замирания — отдельной строкой и только когда их стало больше: это
                // событие, а не число, которое дышит.
                val freezes = (incoming.members["freezeCount"] as? Number)?.toLong() ?: 0L
                if (freezes > froze) {
                    froze = freezes
                    Journal.trouble(
                        LogCode.CALL,
                        "видео собеседника замирало",
                        "раз" to freezes,
                        "всего с" to ((incoming.members["totalFreezesDuration"] as? Number)?.toDouble()?.let { tenth(it) } ?: "—"),
                    )
                }

                // ── ПРИШЛО НЕКРАТНОЕ (ПЛАН-ВИДЕО.md В2.4) ────────────────────
                //
                // Отправитель вне обрезки до кратного 16: старая сборка, программный кодер,
                // ПК на нижнем слое, чужое приложение. Одна строка на смену размера.
                val inSize = "" + w + "×" + h
                val inW = (w as? Number)?.toInt()
                val inH = (h as? Number)?.toInt()
                if (inW != null && inH != null && !CenterCrop.aligned(inW, inH) && inSize != misaligned) {
                    Journal.trouble(LogCode.CALL, "пришло некратное", "кадр" to inSize, "раскодировщик" to decoder)
                }
                misaligned = inSize

                val line = "" + w + "×" + h + "|" + (kbit / 100) + "|" + decoder
                if (line == said) continue
                said = line
                Journal.note(
                    LogCode.CALL,
                    "приходящее видео",
                    "кадр" to ("" + w + "×" + h),
                    "кбит/с" to if (kbit < 0) "считаем" else kbit.toString(),
                    "раскодировщик" to decoder,
                    "раскод мс" to (decodeMs?.let { tenth(it) } ?: "—"),
                    "выброшено" to (dropped ?: "—"),
                )
            }
        }
    }

    /**
     * Стадия звонка — **из состояния комнаты и числа участников вместе**.
     *
     * ── ПОЧЕМУ НЕ ХВАТАЕТ ОДНОГО `Room.State` ───────────────────────────────
     *
     * `CONNECTED` у комнаты значит «мы вошли», а не «нас соединили». Войти можно одному
     * и ждать; собеседник в этот момент ещё слушает гудки. Раньше стадия бралась прямо
     * отсюда, и разговор «начинался» в момент набора — таймер шёл с первой секунды
     * (живой прогон 2026-09-20).
     *
     * ── И ПОЧЕМУ НЕ ХВАТАЕТ ОДНИХ УЧАСТНИКОВ ────────────────────────────────
     *
     * Пустая комната значит разное до и после разговора: сперва «ещё не ответили», потом
     * «собеседник отключился». Отличает их [everAnswered] — был ли кто-нибудь тут хоть
     * раз. Без него положенная собеседником трубка выглядела бы как продолжение набора.
     */
    private fun settle(room: Room, callId: String) {
        val others = room.remoteParticipants.keys.map { it.value }
        if (others.isNotEmpty()) everAnswered = true
        val roomState = room.state
        val stage = when {
            roomState == Room.State.DISCONNECTED -> CallStage.Ended
            roomState == Room.State.RECONNECTING -> CallStage.Reconnecting
            roomState != Room.State.CONNECTED -> CallStage.Connecting
            // Групповой: вошёл в комнату — ты в звонке, даже если пока один. Пустая
            // комната здесь не конец: остальные входят и выходят, звонок идёт (ГЗ3).
            group != null -> CallStage.Connected
            others.isNotEmpty() -> CallStage.Connected
            // В комнате одни. До ответа это набор, после — разговор кончился.
            everAnswered -> CallStage.Ended
            else -> CallStage.Connecting
        }
        _state.value = _state.value.copy(
            callId = callId,
            others = others,
            inRoom = roomState == Room.State.CONNECTED,
            stage = stage,
            peerLeft = group == null && stage == CallStage.Ended && everAnswered && roomState == Room.State.CONNECTED,
        )
    }

    /**
     * Участники группового звонка и пауза создателя — опросом раз в [PEERS_POLL_MS].
     *
     * Опрос, а не подписка на каждое поле каждого участника: полей пять, участников до 25,
     * и сотня подписок, гаснущих и заводящихся при каждом входе, дороже списка раз в
     * полсекунды. Список сравнивается целиком — поток одинаковое не повторяет.
     */
    private fun watchGroup(room: Room) {
        watchers += scope.launch {
            var saidPaused: Boolean? = null
            while (isActive) {
                val list = room.remoteParticipants.values.map { who ->
                    val identity = who.identity?.value.orEmpty()
                    val camera = who.videoTrackPublications.firstOrNull { (pub, _) -> pub.source == Track.Source.CAMERA }
                    // «Больше 8 — только голос» и «скрыть видео»: отписываемся и от тех, кто
                    // включил камеру после нашего решения.
                    if (!takeRemote) (camera?.first as? RemoteTrackPublication)?.takeIf { it.subscribed }?.setSubscribed(false)
                    val track = (camera?.second as? VideoTrack)?.takeIf { takeRemote && !camera.first.muted }
                    val handle = track?.let { t ->
                        peerHandles[identity]?.takeIf { it.track === t } ?: LiveKitVideoHandle(room, t).also { peerHandles[identity] = it }
                    }
                    if (handle == null) peerHandles.remove(identity)
                    CallPeer(
                        identity = identity,
                        userId = userOfIdentity(identity),
                        microphoneOn = who.isMicrophoneEnabled,
                        cameraOn = who.isCameraEnabled,
                        speaking = who.isSpeaking,
                        paused = who.attributes[VideoPause.ATTRIBUTE] == VideoPause.PAUSED,
                        video = handle,
                        videoLoss = peerLoss[identity],
                        bench = who.attributes[BenchAttribute.ATTRIBUTE]?.takeIf { it.isNotBlank() },
                        incoming = peerNumbers[identity],
                    )
                }.sortedBy { it.identity }
                if (list != _peers.value) _peers.value = list
                val paused = roomPaused(room.metadata)
                if (paused != _state.value.roomPaused) _state.value = _state.value.copy(roomPaused = paused)
                if (paused != saidPaused) {
                    if (saidPaused != null) Journal.note(LogCode.CALL, "пауза группового звонка", "на паузе" to paused)
                    saidPaused = paused
                }
                delay(PEERS_POLL_MS)
            }
        }
    }

    /**
     * Сказать участникам группового, чем публикую: набор, кодек, кадр, кодер, обрезка —
     * для их журнала стенда (заказчик 2026-10-01, 5а). Только перемену: строка без полосы.
     */
    private fun announceBench(room: Room, codec: String?, size: String, coder: String) {
        val preset = publishing
        val kind = when (hardware(coder)) {
            true -> "апп"
            false -> "прог"
            null -> "—"
        }
        val crop = if (preset?.video?.noCrop == true) "без обрезки" else "обрезка 16"
        val text = listOf(preset?.name ?: "—", (codec ?: "—") + " " + size, kind + " " + coder, crop).joinToString(" · ")
        if (text == benchSaid) return
        benchSaid = text
        runCatching { room.localParticipant.updateAttributes(mapOf(BenchAttribute.ATTRIBUTE to text)) }
            .onFailure { Journal.trouble(LogCode.CALL, "не сказали участникам свой набор", "причина" to (it.message ?: it::class.simpleName)) }
    }

    /** Пауза создателя — в данных комнаты `{"paused":true}` (решение 5). */
    private fun roomPaused(metadata: String?): Boolean =
        metadata?.replace(" ", "")?.contains("\"paused\":true") == true

    override suspend fun setVideoCeiling(height: Int?) {
        val live = room ?: return
        if (height == ceilingHeight) return
        ceilingHeight = height
        if (height == null) {
            Journal.note(LogCode.CALL, "групповой: участников много — только голос")
            setCamera(false)
            return
        }
        val preset = publishing ?: return
        val capped = preset.cappedTo(height)
        Journal.note(
            LogCode.CALL, "групповой: потолок видео по числу участников",
            "кадр" to ("" + capped.video.width + "×" + capped.video.height), "бит/с" to capped.video.bitrate,
        )
        live.videoTrackCaptureDefaults = LocalVideoTrackOptions(
            captureParams = VideoCaptureParameter(width = capped.video.width, height = capped.video.height, maxFps = capped.video.fps),
        )
        applyDefaults(live, capped.video, target ?: capped.video.codec, null)
        republishCamera(live, "потолок по числу участников")
    }

    /**
     * Своя дорожка — та, что видит собеседник.
     *
     * Следим потоком, а не берём один раз после `setCameraEnabled`: дорожка поднимается
     * не мгновенно, и взятая сразу оказалась бы `null` ровно в тот момент, когда человек
     * ждёт своё изображение.
     */
    /**
     * Что на самом деле уходит наверх: размер кадра и **почему** он такой.
     *
     * ── ЗАЧЕМ ЭТО В ЖУРНАЛЕ ─────────────────────────────────────────────────
     *
     * Полосы у realme 2026-09-20 шли ступенями — пару секунд в начале, потом ещё
     * немного, дальше чисто. Объяснение («WebRTC роняет разрешение ступенями, и на одной
     * из них кодер ломается») оказалось верным, но **вывели его из рассказа человека, а
     * не из журнала**: журнал про размер кадра не знал ничего.
     *
     * Теперь знает. Пишем **только смену** — размер за звонок меняется единицы раз, а
     * опрос идёт каждые три секунды, и лента из сорока одинаковых строк не объясняет
     * ничего.
     *
     * `qualityLimitationReason` — слово самого WebRTC о том, кто ужал картинку:
     * `bandwidth` (полоса), `cpu` (телефон не тянет), `none`. Без него «стало 320×240»
     * не отличить от «мы сами так попросили».
     *
     * ── И КТО ИМЕННО КОДИРУЕТ ───────────────────────────────────────────────
     *
     * `encoderImplementation` — имя работающего кодера. Оно решает спор, который иначе
     * решался бы рассуждением: аппаратный `OMX.sprd.h264.encoder` у realme или
     * программный запасной.
     *
     * Вопрос не праздный. В upstream WebRTC аппаратный H.264 разрешён только для
     * Qualcomm и Exynos; Unisoc в список не входит, и если он всё-таки работает —
     * работает без чьей-либо проверки качества. На нём и строится объяснение стартовых
     * полос. Пока имени нет в журнале, объяснение остаётся догадкой.
     *
     * Битрейт считается разницей `bytesSent` между опросами: мгновенного числа в
     * статистике нет, а среднее за звонок ничего не говорит о провале на старте.
     */
    private fun watchOutgoing(room: Room) {
        watchers += scope.launch {
            var said = ""
            var sent = -1L
            var lastCoder = "—"
            var misaligned = ""
            val check = CodecCheck()
            while (isActive) {
                delay(STATS_EVERY_MS)
                val track = room.localParticipant.videoTrackPublications
                    .firstNotNullOfOrNull { it.second as? VideoTrack } ?: continue
                // Отчёт приходит пустым, пока дорожка не поднялась, — это не беда, а
                // «ещё рано»: просто ждём следующего опроса.
                val report = runCatching { track.getRTCStats()?.statsMap?.values }.getOrNull() ?: continue
                val outgoing = report.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" } ?: continue

                // ── УХОДИТ ЛИ ПРОСИМЫЙ КОДЕК (заказчик 2026-09-29) ───────────────
                //
                // «кодек публикации шлём=» — просьба. Что ушло на деле, говорит `codecId`
                // исходящей дорожки. Расходились они молча: Honor просил H.264 и слал VP8,
                // Samsung с кратностью 16 просил VP9 и слал VP8.
                val sentMime = outgoing.members["codecId"]?.toString()?.let { id ->
                    report.firstOrNull { it.id == id }?.members?.get("mimeType")?.toString()
                }
                val mismatch = check.next(target, publishing?.video?.backup, sentMime)
                if (mismatch != _state.value.codecMismatch) {
                    _state.value = _state.value.copy(codecMismatch = mismatch)
                    if (mismatch != null) {
                        Journal.trouble(
                            LogCode.CALL, "уходит не тот кодек, что просили",
                            "просили" to mismatch.asked, "уходит" to mismatch.sent,
                        )
                    } else {
                        Journal.note(LogCode.CALL, "уходит просимый кодек", "кодек" to (sentMime?.removePrefix("video/") ?: "—"))
                    }
                }

                val w = outgoing.members["frameWidth"] ?: continue
                val h = outgoing.members["frameHeight"] ?: continue
                val why = outgoing.members["qualityLimitationReason"]?.toString() ?: "—"
                val coder = outgoing.members["encoderImplementation"]?.toString() ?: "—"

                // ── АППАРАТНЫЙ СЛОМАЛСЯ — WEBRTC ПЕРЕШЁЛ САМ (ПЛАН-ВИДЕО.md В1) ─
                //
                // При ошибке аппаратного кодера WebRTC молча берёт программный. Раньше это
                // было видно только по смене имени в строках раз в три секунды. Своё
                // выключение переключателем — не поломка, о нём своя строка.
                if (hardware(lastCoder) == true && hardware(coder) == false && coding.encode) {
                    Journal.trouble(
                        LogCode.CALL,
                        "аппаратный кодер сломался, WebRTC перешёл на программный",
                        "был" to lastCoder,
                        "стал" to coder,
                    )
                }
                lastCoder = coder

                val bytes = (outgoing.members["bytesSent"] as? Number)?.toLong() ?: 0L
                // Первый опрос сравнивать не с чем: считать от нуля значило бы объявить
                // весь накопленный трафик мгновенным битрейтом.
                val kbit = if (sent < 0) -1L else (bytes - sent) * 8 / (STATS_EVERY_MS / 1000) / 1000
                sent = bytes

                // Битрейт округляется до сотни: он дышит постоянно, и без округления
                // строка менялась бы каждые три секунды, ничего не объясняя.
                val size = "" + w + "×" + h

                // ── УШЛО НЕКРАТНОЕ (ПЛАН-ВИДЕО.md В2.4) ──────────────────────
                //
                // В кодер попал кадр не кратного 16 размера — дыра в обрезке. Аппаратный
                // путь режется всегда; программный (libvpx) обрезать нечем — он и даст эту
                // строку. Одна строка на смену размера.
                val outW = (w as? Number)?.toInt()
                val outH = (h as? Number)?.toInt()
                if (outW != null && outH != null && !CenterCrop.aligned(outW, outH) && size != misaligned) {
                    Journal.trouble(
                        LogCode.CALL, "ушло некратное",
                        "кадр" to size, "кодер" to coder,
                        "путь" to when (hardware(coder)) { true -> "апп"; false -> "прог"; null -> "—" },
                    )
                }
                misaligned = size

                if (group != null) announceBench(room, sentMime?.removePrefix("video/"), size, coder)
                val line = size + "|" + why + "|" + coder + "|" + (kbit / 100)
                if (line == said) continue
                said = line
                Journal.note(
                    LogCode.CALL,
                    "уходящее видео",
                    "кадр" to size,
                    "ужато" to why,
                    "кодер" to coder,
                    "кбит/с" to if (kbit < 0) "считаем" else kbit.toString(),
                )
            }
        }
    }

    private fun watchLocalVideo(room: Room) {
        watchers += scope.launch {
            room.localParticipant::videoTrackPublications.flow.collect { published ->
                val track = published.firstOrNull()?.second as? VideoTrack
                show(_localVideo, room, track)
                _state.value = _state.value.copy(cameraOn = track != null)
                autoSound(cameraOn = track != null)
            }
        }
    }

    /**
     * Дорожка собеседника.
     *
     * **Берём первую попавшуюся, и этого сегодня достаточно:** звонок один на один, в
     * комнате двое. Групповой звонок потребует отдельного решения о том, кого показывать
     * крупно, — и это будет не здесь, а на экране.
     *
     * Подписка автоматическая: принимать чужое видео разрешения не требует ни у системы,
     * ни у человека. Отписка — по кнопке «скрыть» (ЗВ11).
     */
    private fun watchRemoteVideo(room: Room) {
        watchers += scope.launch {
            room::remoteParticipants.flow.collectLatest { participants ->
                // Сперва посмотреть на то, что есть сейчас: участник мог войти уже с
                // видео, а мог и уйти — тогда картинку надо снять.
                pickRemote(room)
                // ── А ДАЛЬШЕ СЛЕДИТЬ ЗА КАЖДЫМ ──────────────────────────────
                //
                // **Участник приходит в комнату БЕЗ видео и включает его потом.**
                // `remoteParticipants` меняется, когда меняется состав, а не когда кто-то
                // из них опубликовал дорожку. Раньше смотрели только на состав — и
                // включённая посреди разговора камера не появлялась у собеседника вовсе
                // (заказчик 2026-09-20).
                //
                // `collectLatest` снаружи гасит эти подписки, когда состав сменился, и
                // заводит новые: следить за ушедшим незачем.
                coroutineScope {
                    for (who in participants.values) {
                        launch { who::videoTrackPublications.flow.collect { pickRemote(room) } }
                    }
                }
            }
        }
    }

    /** Какую чужую дорожку показывать. Одна: звонок один на один, в комнате двое. */
    private fun pickRemote(room: Room) {
        if (!takeRemote) {
            show(_remoteVideo, room, null)
            _state.value = _state.value.copy(remoteVideoShown = false)
            return
        }
        val track = room.remoteParticipants.values
            .flatMap { it.videoTrackPublications }
            .firstNotNullOfOrNull { it.second as? VideoTrack }
        show(_remoteVideo, room, track)
        _state.value = _state.value.copy(remoteVideoShown = track != null)
    }

    /**
     * Показать дорожку — **и не трогать ничего, если она та же самая**.
     *
     * Состояние комнаты обновляется десятки раз за звонок: кто-то включил микрофон,
     * сменилось качество, подъехала подписка. Раньше на каждое такое обновление
     * заводилась новая ручка, поток считал её новым значением, а экран перебирал под ней
     * поверхность. Картинка при этом замирала, хотя дорожка шла: в журнале «видео
     * собеседника идёт=true» держалось до конца звонка (отчёт `RX9A` 2026-09-20).
     */
    private fun show(where: MutableStateFlow<VideoHandle?>, room: Room, track: VideoTrack?) {
        val shown = (where.value as? LiveKitVideoHandle)?.track
        if (shown === track) return
        where.value = track?.let { LiveKitVideoHandle(room, it) }
    }
}

/** Как часто спрашиваем числа у WebRTC. Три секунды: ступень размера длится дольше. */
private const val STATS_EVERY_MS = 3_000L

/** Как часто перечитываем участников группового звонка. */
private const val PEERS_POLL_MS = 500L

/** Как часто пересматриваем кодек под собеседников. */
private const val PEERS_EVERY_MS = 1_000L

/** Пауза между отпиской и подпиской при смене раскодировщика. */
private const val RESUBSCRIBE_PAUSE_MS = 300L

/** Отметка «собеседник не сказал» в [LiveKitCallEngine.peerTold]. */
private const val UNKNOWN = "?"

/** Сколько раз просим дорожку подняться и сколько ждём между попытками. */
private const val PUBLISH_TRIES = 3
private const val PUBLISH_RETRY_MS = 500L

/** Наш кодек в кодек SDK. AV1 в нашем перечне нет — решение заказчика, а не SDK. */
private fun VideoCodec.toLiveKit(): LkVideoCodec = when (this) {
    VideoCodec.H264 -> LkVideoCodec.H264
    VideoCodec.VP9 -> LkVideoCodec.VP9
    VideoCodec.H265 -> LkVideoCodec.H265
    VideoCodec.VP8 -> LkVideoCodec.VP8
}

/** Число с одним знаком после запятой — для журнала. */
private fun tenth(value: Double): String = (kotlin.math.round(value * 10) / 10).toString()

/** Сейчас, миллисекунды монотонных часов. Для разниц, а не для отметок времени. */
private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

/**
 * Аппаратный ли кодер — по его собственному имени.
 *
 * Вендорский кодек зовётся `OMX.<вендор>.*` или `c2.<вендор>.*`; программные кодеки
 * Google — `OMX.google.*` и `c2.android.*`, а libwebrtc свои зовёт `libvpx`, `OpenH264`
 * и через обёртку `SimulcastEncoderAdapter`.
 *
 * `null` там, где имя ни на что не похоже: соврать «программный» хуже, чем сказать
 * «не знаем», — на этом числе решается судьба VP9 (С-В8).
 */
private fun hardware(name: String): Boolean? {
    val lower = name.lowercase()
    if (lower.contains("libvpx") || lower.contains("openh264") || lower.contains("ffmpeg")) return false
    if (lower.contains("omx.google") || lower.contains("c2.android")) return false
    if (lower.startsWith("omx.") || lower.startsWith("c2.") || lower.contains("mediacodec")) return true
    return null
}
