package io.tima.core.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Свой движок на Android — Media3 ExoPlayer (ПЛАН-(ВМ) ВМ5): файлы, HLS, скорость с тоном. */
internal class ExoOwnPlayer(val context: Context) : OwnPlayer {
    // Заголовок браузера — тот же, что у запроса страницы VK: её ссылка на поток помнит браузер (ВМ8).
    val exo: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(
                    context,
                    androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(MEDIA_USER_AGENT).setAllowCrossProtocolRedirects(true),
                ),
            ),
        )
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(OwnPlayerState())
    override val state: StateFlow<OwnPlayerState> = _state.asStateFlow()
    private var ticker: Job? = null

    init {
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = refresh()
            override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
            override fun onRenderedFirstFrame() {
                _state.value = _state.value.copy(started = true)
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _state.value = _state.value.copy(width = videoSize.width, height = videoSize.height)
            }
            override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
                val title = listOfNotNull(mediaMetadata.artist?.toString(), mediaMetadata.title?.toString())
                    .filter { it.isNotBlank() }.joinToString(" — ")
                _state.value = _state.value.copy(title = title.ifBlank { null })
            }
            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(error = error.errorCodeName, playing = false)
            }
        })
        ticker = scope.launch {
            while (isActive) {
                refresh()
                delay(250)
            }
        }
    }

    private fun refresh() {
        val s = _state.value
        val duration = exo.duration.takeIf { it > 0 } ?: 0
        val live = exo.isCurrentMediaItemLive
        // Звук без картинки «начался», когда позиция пошла вперёд: первого кадра у него не бывает.
        val started = s.started || (exo.isPlaying && exo.currentPosition > 300)
        _state.value = s.copy(
            playing = exo.isPlaying,
            positionMs = exo.currentPosition.coerceAtLeast(0),
            durationMs = if (live) 0 else duration,
            buffering = exo.playbackState == Player.STATE_BUFFERING,
            ended = exo.playbackState == Player.STATE_ENDED,
            live = live,
            started = started,
        )
    }

    override fun open(url: String, startMs: Long, play: Boolean) {
        _state.value = OwnPlayerState(speed = _state.value.speed)
        exo.setMediaItem(MediaItem.fromUri(url), startMs.coerceAtLeast(0))
        exo.playWhenReady = play
        exo.prepare()
    }

    override fun play() {
        if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0)
        exo.play()
    }

    override fun pause() = exo.pause()
    override fun seek(ms: Long) = exo.seekTo(ms.coerceAtLeast(0))

    override fun setSpeed(speed: Float) {
        exo.playbackParameters = PlaybackParameters(speed)
        _state.value = _state.value.copy(speed = speed)
    }

    override fun release() {
        scope.cancel()
        exo.release()
    }
}

@Composable
actual fun rememberOwnPlayer(): OwnPlayer? {
    val context = LocalContext.current
    val player = remember { ExoOwnPlayer(context.applicationContext) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

@OptIn(UnstableApi::class)
@Composable
actual fun OwnPlayerSurface(player: OwnPlayer, modifier: Modifier) {
    val exo = (player as? ExoOwnPlayer)?.exo ?: return
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = exo
            }
        },
        modifier = modifier,
    )
}
