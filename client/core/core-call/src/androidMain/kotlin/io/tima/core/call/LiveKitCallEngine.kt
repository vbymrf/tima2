package io.tima.core.call

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.VideoCaptureParameter
import io.livekit.android.room.track.VideoCodec as LkVideoCodec
import io.livekit.android.util.flow
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private var room: Room? = null

    override suspend fun connect(door: CallDoor, publish: PublishPreset?) {
        // Пресет применяется ПРИ СОЗДАНИИ комнаты, а не при публикации: кодек и слои
        // участвуют в согласовании, и менять их потом — пересогласование, а иногда разрыв
        // (С-В5 в плане стенда).
        val options = RoomOptions(
            adaptiveStream = publish?.video?.adaptiveStream ?: true,
            dynacast = publish?.video?.dynacast ?: true,
            videoTrackPublishDefaults = publish?.video?.let { video ->
                VideoTrackPublishDefaults(
                    videoCodec = video.codec.toLiveKit().codecName,
                    // ЯВНО, а не умолчанием SDK: у SVC-кодека он молча ставит запасным
                    // VP8 с simulcast, и прогон «VP9 SVC» тогда мерит не VP9
                    // (ПЛАН-СТЕНДА §5а, «ловушка»).
                    simulcast = video.layers == LayerMode.Simulcast,
                    scalabilityMode = video.scalability.takeIf { video.layers == LayerMode.Svc },
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
            created.localParticipant.setMicrophoneEnabled(true)
            _state.value = _state.value.copy(microphoneOn = true)
        } catch (e: Throwable) {
            // Причина словами и в состоянии: звонок, который «просто не начался», —
            // худшее из состояний, потому что человек видит пустоту и не знает, чего ждать.
            _state.value = _state.value.copy(
                stage = CallStage.Ended,
                trouble = e.message ?: e::class.simpleName ?: "не подключились",
            )
        }
    }

    override suspend fun disconnect() {
        room?.disconnect()
        room = null
        _state.value = _state.value.copy(stage = CallStage.Ended)
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
            room::state.flow.collect { roomState ->
                _state.value = _state.value.copy(
                    callId = callId,
                    stage = when (roomState) {
                        Room.State.CONNECTING -> CallStage.Connecting
                        Room.State.CONNECTED -> CallStage.Connected
                        Room.State.RECONNECTING -> CallStage.Reconnecting
                        Room.State.DISCONNECTED -> CallStage.Ended
                    },
                )
            }
        }
        scope.launch {
            room::remoteParticipants.flow.collect { participants ->
                _state.value = _state.value.copy(others = participants.keys.map { it.value })
            }
        }
    }
}

/** Наш кодек в кодек SDK. AV1 в нашем перечне нет — решение заказчика, а не SDK. */
private fun VideoCodec.toLiveKit(): LkVideoCodec = when (this) {
    VideoCodec.H264 -> LkVideoCodec.H264
    VideoCodec.VP9 -> LkVideoCodec.VP9
    VideoCodec.H265 -> LkVideoCodec.H265
}
