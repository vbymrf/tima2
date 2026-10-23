package io.tima.core.media

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Служба воспроизведения (ПЛАН-(ВМ) Р22): сессия Media3 над тем же ExoPlayer, что играет «Аудио» и
 * «Поток». Пока он играет, Media3 сама держит уведомление с ▶/⏸ и службу на переднем плане — звук
 * идёт при погашенном экране; пауза из уведомления — пауза в сообщении.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        Background.service = this
        val player = Background.player ?: return
        session = MediaSession.Builder(this, player).build().also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Смахнули из недавних и звук не идёт — служба не нужна.
        if (session?.player?.isPlaying != true) stopSelf()
    }

    override fun onDestroy() {
        if (Background.service === this) Background.service = null
        session?.release()
        session = null
        super.onDestroy()
    }

    internal object Background {
        @Volatile var player: Player? = null
        @Volatile var service: PlaybackService? = null
    }
}

actual fun keepPlaying(player: OwnPlayer, on: Boolean) {
    val exo = player as? ExoOwnPlayer ?: return
    val intent = Intent(exo.context, PlaybackService::class.java)
    if (on) {
        PlaybackService.Background.player = exo.exo
        runCatching { exo.context.startService(intent) }
    } else {
        // Не `stopService`: после паузы Media3 ещё держит службу на переднем плане и пересоздаёт её
        // через `startForegroundService`, а новая на передний план не выходит — через 30 с Android
        // роняет приложение (Redmi, 2026-10-23). Остановка средствами самой Media3 убирает и
        // уведомление, и передний план.
        runCatching { PlaybackService.Background.service?.pauseAllPlayersAndStopSelf() }
    }
}
