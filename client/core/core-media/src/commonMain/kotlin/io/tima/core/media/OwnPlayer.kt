package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * Свой движок окна проигрывания (ПЛАН-(ВМ) ВМ5): видео и звук по прямой ссылке, потоки HLS.
 * Android — Media3 ExoPlayer, ПК — Media Foundation (мост ComposeMediaPlayer). Второй, браузерный
 * движок — отдельно (ВМ6); окно переключается между ними, если этот не смог (ВМ7).
 */
interface OwnPlayer {
    val state: StateFlow<OwnPlayerState>

    /** Открыть [url] с [startMs]; [play] — сразу играть. */
    fun open(url: String, startMs: Long = 0, play: Boolean = true)
    fun play()
    fun pause()
    fun seek(ms: Long)

    /** Скорость с сохранением тона (Р39). */
    fun setSpeed(speed: Float)
    fun release()
}

data class OwnPlayerState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** 0 — неизвестна или эфир. */
    val durationMs: Long = 0,
    val buffering: Boolean = false,
    /** Не смог: адрес не открылся, формат не играется. Причина — для журнала. */
    val error: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** Пошёл первый кадр или звук — сторож смены движка (ВМ7) смотрит сюда. */
    val started: Boolean = false,
    val ended: Boolean = false,
    /** Прямой эфир: шкалы времени нет. */
    val live: Boolean = false,
    val speed: Float = 1f,
    /** Эфир: название песни, если поток его сообщает (§3.8: «Сейчас: …»). */
    val title: String? = null,
)

/** Свой движок этой платформы; `null` — не умеет (iOS, ПК не на Windows). */
@Composable
expect fun rememberOwnPlayer(): OwnPlayer?

/** Картинка своего движка — вписана в [modifier] с сохранением пропорций. */
@Composable
expect fun OwnPlayerSurface(player: OwnPlayer, modifier: Modifier)
