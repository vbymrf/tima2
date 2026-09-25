package io.tima.core.call

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.AudioTrackPublishDefaults
import io.livekit.android.room.participant.BackupVideoCodec
import io.livekit.android.room.participant.ConnectionQuality
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoCodec as LkVideoCodec
import io.livekit.android.util.flow
import livekit.org.webrtc.HardwareVideoEncoderFactory
import livekit.org.webrtc.RtpParameters.DegradationPreference
import livekit.org.webrtc.SoftwareVideoEncoderFactory
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
        val created = LiveKit.create(appContext = context, options = options)
        publish?.let { publishVideoAs(created, it.video, exact = it.exact) }
        room = created
        everAnswered = false
        watch(created, door.callId)
        _state.value = CallState(stage = CallStage.Connecting, callId = door.callId)
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
        val encodable = encodableCodecs()
        val choice = CodecChoice.pick(video.codec, video.backup, encodable, exact)
        val can = encodable.joinToString(", ") { it.name }.ifEmpty { "не узнали" }
        val backup = video.backup
        if (backup != null && !backup.backupCapable) {
            // SDK такой запасной не посылает (VideoCodec.backupCapable) — говорим, что его
            // нет, чтобы прогон не числил запасным то, чего в сети не бывает.
            Journal.note(LogCode.CALL, "запасной не годится SDK, его нет", "запасной" to backup.name)
        }
        if (exact && encodable.isNotEmpty() && video.codec !in encodable) {
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
     * Что умеет кодер телефона — **тем же набором фабрик, что берёт SDK**: аппаратная
     * WebRTC плюс программная (так устроена его `SimulcastVideoEncoderFactoryWrapper`).
     * Аппаратная отбрасывает то, чем WebRTC на этой версии Android не пользуется, —
     * ровно то, что нам и нужно знать.
     *
     * Не удалось спросить — пустое множество, и [CodecChoice] оставит пресет как есть.
     */
    private fun encodableCodecs(): Set<VideoCodec> = try {
        val infos = HardwareVideoEncoderFactory(null, true, true).supportedCodecs.toList() +
            SoftwareVideoEncoderFactory().supportedCodecs.toList()
        infos.mapNotNull { CodecChoice.fromWebRtcName(it.name) }.toSet()
    } catch (e: Throwable) {
        Journal.trouble(LogCode.CALL, "не узнали кодеки телефона", "причина" to (e.message ?: e::class.simpleName))
        emptySet()
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
        // Ширина — чтобы сложить копии от меньшей к большей; строка — то, что покажем.
        val upFrames = mutableListOf<Pair<Int, String>>()
        var downFrame: Pair<Int, String>? = null

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
        watchIncoming(room)
        watchQuality(room)
        watchBreaks(room)
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
    private fun watchIncoming(room: Room) {
        watchers += scope.launch {
            var said = ""
            var got = -1L
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

                val line = "" + w + "×" + h + "|" + (kbit / 100)
                if (line == said) continue
                said = line
                Journal.note(
                    LogCode.CALL,
                    "приходящее видео",
                    "кадр" to ("" + w + "×" + h),
                    "кбит/с" to if (kbit < 0) "считаем" else kbit.toString(),
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
            peerLeft = stage == CallStage.Ended && everAnswered && roomState == Room.State.CONNECTED,
        )
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
            while (isActive) {
                delay(STATS_EVERY_MS)
                val track = room.localParticipant.videoTrackPublications
                    .firstNotNullOfOrNull { it.second as? VideoTrack } ?: continue
                val outgoing = runCatching {
                    // Отчёт приходит пустым, пока дорожка не поднялась, — это не беда, а
                    // «ещё рано»: просто ждём следующего опроса.
                    track.getRTCStats()?.statsMap?.values
                        ?.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }
                }.getOrNull() ?: continue

                val w = outgoing.members["frameWidth"] ?: continue
                val h = outgoing.members["frameHeight"] ?: continue
                val why = outgoing.members["qualityLimitationReason"]?.toString() ?: "—"
                val coder = outgoing.members["encoderImplementation"]?.toString() ?: "—"

                val bytes = (outgoing.members["bytesSent"] as? Number)?.toLong() ?: 0L
                // Первый опрос сравнивать не с чем: считать от нуля значило бы объявить
                // весь накопленный трафик мгновенным битрейтом.
                val kbit = if (sent < 0) -1L else (bytes - sent) * 8 / (STATS_EVERY_MS / 1000) / 1000
                sent = bytes

                // Битрейт округляется до сотни: он дышит постоянно, и без округления
                // строка менялась бы каждые три секунды, ничего не объясняя.
                val size = "" + w + "×" + h
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
