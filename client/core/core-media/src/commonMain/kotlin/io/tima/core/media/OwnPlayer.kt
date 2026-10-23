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

    /**
     * Открыть [url] с [startMs]; [play] — сразу играть; [hls] — это поток HLS, даже если адрес не кончается
     * на m3u8 (варианты качества VK — без окончания, Media3 принимал их за файл, 2026-10-23).
     */
    fun open(url: String, startMs: Long = 0, play: Boolean = true, hls: Boolean = false)
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
    /**
     * Проигрыватель сайта ушёл с нашего видео — на другой ролик или страницу (заказчик 2026-10-23, 4а):
     * время и метки уже не наши, «Перезапустить» горит. Снимается новым `open`.
     */
    val elsewhere: Boolean = false,
)

/** Свой движок этой платформы; `null` — не умеет (iOS, ПК не на Windows). */
@Composable
expect fun rememberOwnPlayer(): OwnPlayer?

/** Картинка своего движка — вписана в [modifier] с сохранением пропорций. */
@Composable
expect fun OwnPlayerSurface(player: OwnPlayer, modifier: Modifier)

/**
 * Заголовок браузера для запросов к сайтам видео (ПЛАН-(ВМ) ВМ8): ссылка VK на поток помнит, каким
 * браузером её запросили (`srcAg`), и другому не отдаёт — свой движок Android ходит с тем же.
 */
const val MEDIA_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
