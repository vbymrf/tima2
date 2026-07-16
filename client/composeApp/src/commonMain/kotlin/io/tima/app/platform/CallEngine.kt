package io.tima.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/** Состояние медиа-подключения к комнате LiveKit. */
enum class CallMediaState { Idle, Connecting, Connected, Failed }

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

    /** Подключиться к комнате; publishMic — публиковать ли микрофон (слушатель — нет), video — камеру. */
    suspend fun connect(url: String, token: String, video: Boolean, publishMic: Boolean)
    fun setMic(on: Boolean)
    fun setCamera(on: Boolean)
    fun disconnect()
}

/** Запросить разрешения (микрофон; при video ещё камера). true — выданы. */
expect suspend fun ensureCallPermissions(video: Boolean): Boolean

/** Видео звонка: удалённый участник на весь блок + локальная превьюшка. Desktop — текст-заглушка. */
@Composable
expect fun CallVideoView(engine: CallEngine, modifier: Modifier)
