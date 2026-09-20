package io.tima.core.call

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoEncoding
import io.livekit.android.room.track.VideoCodec as LkVideoCodec
import io.livekit.android.util.flow
import livekit.org.webrtc.RtpParameters.DegradationPreference
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
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

    /** Принимаем ли чужое видео. Выключается кнопкой «скрыть» (ЗВ11). */
    private var takeRemote: Boolean = true

    /** Отвечал ли кто-нибудь. Отличает «ещё не ответили» от «собеседник ушёл». */
    private var everAnswered: Boolean = false

    override suspend fun connect(door: CallDoor, publish: PublishPreset?) {
        // Пресет применяется ПРИ СОЗДАНИИ комнаты, а не при публикации: кодек и слои
        // участвуют в согласовании, и менять их потом — пересогласование, а иногда разрыв
        // (С-В5 в плане стенда).
        val options = RoomOptions(
            adaptiveStream = publish?.video?.adaptiveStream ?: true,
            dynacast = publish?.video?.dynacast ?: true,
            videoTrackPublishDefaults = publish?.video?.let { video ->
                VideoTrackPublishDefaults(
                    // Битрейт задаём сами: без него он принадлежал умолчанию SDK, и цена
                    // за `MaintainResolution` ложилась на чёткость молча.
                    videoEncoding = VideoEncoding(maxBitrate = video.bitrate, maxFps = video.fps),
                    videoCodec = video.codec.toLiveKit().codecName,
                    // ЯВНО, а не умолчанием SDK: у SVC-кодека он молча ставит запасным
                    // VP8 с simulcast, и прогон «VP9 SVC» тогда мерит не VP9
                    // (ПЛАН-СТЕНДА §5а, «ловушка»).
                    simulcast = video.layers == LayerMode.Simulcast,
                    scalabilityMode = video.scalability.takeIf { video.layers == LayerMode.Svc },
                    degradationPreference = when (video.degradation) {
                        Degradation.MaintainResolution -> DegradationPreference.MAINTAIN_RESOLUTION
                        Degradation.MaintainFramerate -> DegradationPreference.MAINTAIN_FRAMERATE
                        Degradation.Balanced -> DegradationPreference.BALANCED
                    },
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
        val created = LiveKit.create(appContext = context, options = options)
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
            created.localParticipant.setMicrophoneEnabled(true)
            _state.value = _state.value.copy(microphoneOn = true)
            // **Запись именно об этом шаге, а не о звонке вообще.** Немой звонок
            // 2026-09-20 был невидим в отчёте ровно потому, что журнал знал «звонок
            // начат» и «звонок закончен», а поднялась ли дорожка — не знал никто.
            Journal.note(LogCode.CALL, "микрофон опубликован")
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

    override suspend fun disconnect() {
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
        scope.launch {
            room::state.flow.collect { settle(room, callId) }
        }
        scope.launch {
            room::remoteParticipants.flow.collect { settle(room, callId) }
        }
        watchLocalVideo(room)
        watchRemoteVideo(room)
        watchOutgoing(room)
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
        scope.launch {
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
        scope.launch {
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
        scope.launch {
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

/** Наш кодек в кодек SDK. AV1 в нашем перечне нет — решение заказчика, а не SDK. */
private fun VideoCodec.toLiveKit(): LkVideoCodec = when (this) {
    VideoCodec.H264 -> LkVideoCodec.H264
    VideoCodec.VP9 -> LkVideoCodec.VP9
    VideoCodec.H265 -> LkVideoCodec.H265
}
