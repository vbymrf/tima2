package io.tima.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** Состояние медиа-подключения к комнате LiveKit. */
enum class CallMediaState { Idle, Connecting, Connected, Failed }

/**
 * Участник группового звонка. [identity] — user_id из токена LiveKit.
 *
 * Для звонка один на один хватало признака «есть ли кто-то ещё». В группе этого
 * мало: надо показать, кто в звонке, кто говорит и у кого выключен микрофон.
 */
data class CallParticipant(
    val identity: String,
    val name: String = "",
    val speaking: Boolean = false,
    val micOn: Boolean = true,
)

/**
 * Живое медиа звонка/аудио-чата поверх LiveKit (SFU).
 *  - Android: livekit-android (микрофон/камера/WebRTC) — реальный звук и видео.
 *  - Desktop: заглушка (готового WebRTC-клиента для JVM нет) — сигналинг уже прошёл.
 * Токен/URL берутся из сигналинга (CallConnection / VoiceJoinDto).
 */
expect class CallEngine() {
    val state: StateFlow<CallMediaState>
    val micEnabled: StateFlow<Boolean>
    val cameraEnabled: StateFlow<Boolean>

    /** Звук в громком динамике (а не в разговорном, у уха). Видеозвонок начинает с громкого. */
    val speakerOn: StateFlow<Boolean>

    /**
     * Есть ли в комнате кто-то ещё. Для звонящего «подключился» ≠ «мне ответили»:
     * в комнату он входит сразу, а собеседник может и не взять трубку.
     */
    val peerPresent: StateFlow<Boolean>

    /** Кто ещё в комнате. Пусто для звонка один на один, пока никто не вошёл. */
    val participants: StateFlow<List<CallParticipant>>

    /** Подключиться к комнате; publishMic — публиковать ли микрофон (слушатель — нет), video — камеру. */
    suspend fun connect(url: String, token: String, video: Boolean, publishMic: Boolean)
    fun setMic(on: Boolean)
    fun setCamera(on: Boolean)
    fun setSpeaker(on: Boolean)
    fun disconnect()
}

/** Запросить разрешения (микрофон; при video ещё камера). true — выданы. */
expect suspend fun ensureCallPermissions(video: Boolean): Boolean

/** Видео звонка: удалённый участник на весь блок + локальная превьюшка. Desktop — текст-заглушка. */
@Composable
expect fun CallVideoView(engine: CallEngine, modifier: Modifier)

/**
 * Сетка участников группового звонка. Показывает всех, у кого есть видео, и
 * плитку-заглушку с именем у тех, у кого его нет: в группе важно видеть, кто
 * вообще в звонке, а не только тех, кто включил камеру.
 */
@Composable
expect fun CallGridView(engine: CallEngine, names: Map<String, String>, modifier: Modifier)
