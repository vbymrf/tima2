package io.tima.core.media

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Браузерный движок на Android (ПЛАН-(ВМ) ВМ6): `WebView` со своей страницей (VK, Rutube, файл) и
 * android-youtube-player для YouTube (Р14) — та же страница проигрывателя YouTube с верным адресом.
 */
private class AndroidWebPlayer(private val context: Context) : WebPlayer {
    private val _state = MutableStateFlow(OwnPlayerState())
    override val state: StateFlow<OwnPlayerState> = _state.asStateFlow()
    private val _page = MutableStateFlow<WebPage?>(null)
    val page: StateFlow<WebPage?> = _page.asStateFlow()
    private val main = Handler(Looper.getMainLooper())

    var web: WebView? = null
    var youtube: YouTubePlayerView? = null
    private var yt: YouTubePlayer? = null
    private var ytPending: Pair<String, Float>? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): WebView = web ?: WebView(context).apply {
        // Во всё место: с `WRAP_CONTENT` (так `AndroidView` ставит сам) WebView считает высоту страницы
        // по содержимому, «100%» у `html` и `<video>` становится нулём — время шло, а поле стояло чёрным
        // (Redmi и Samsung, 2026-10-23; высота видео 0 — по DevTools).
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        webViewClient = WebViewClient()
        webChromeClient = WebChromeClient()
        addJavascriptInterface(object {
            @JavascriptInterface
            fun post(message: String) {
                main.post { _state.value = _state.value.bridged(message) }
            }
        }, "TimaBridge")
    }.also { web = it }

    fun youtubeView(): YouTubePlayerView = youtube ?: YouTubePlayerView(context).apply {
        enableAutomaticInitialization = false
        initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) {
                yt = youTubePlayer
                ytPending?.let { (id, at) -> youTubePlayer.loadVideo(id, at) }
                ytPending = null
            }
            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                val s = _state.value
                _state.value = when (state) {
                    PlayerConstants.PlayerState.PLAYING -> s.copy(playing = true, buffering = false, ended = false)
                    PlayerConstants.PlayerState.PAUSED -> s.copy(playing = false, buffering = false)
                    PlayerConstants.PlayerState.BUFFERING -> s.copy(buffering = true)
                    PlayerConstants.PlayerState.ENDED -> s.copy(playing = false, ended = true)
                    else -> s
                }
            }
            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                val ms = (second * 1000).toLong()
                _state.value = _state.value.copy(positionMs = ms, started = _state.value.started || ms > 300)
            }
            override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) {
                _state.value = _state.value.copy(durationMs = (duration * 1000).toLong())
            }
            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) {
                _state.value = _state.value.copy(error = "youtube ${error.name}", playing = false)
            }
        }, true, IFramePlayerOptions.Builder(context).controls(1).rel(0).build())
    }.also { youtube = it }

    override fun open(page: WebPage, startMs: Long) {
        _state.value = OwnPlayerState()
        _page.value = page
        if (page.kind == WebPage.Kind.YOUTUBE) {
            youtubeView()
            val at = startMs / 1000f
            yt?.loadVideo(page.videoId, at) ?: run { ytPending = page.videoId to at }
        } else {
            webView().loadDataWithBaseURL(WebPage.ORIGIN, page.html, "text/html", "utf-8", null)
        }
    }

    private fun js(code: String) = web?.evaluateJavascript("window.tima&&tima.$code", null)

    override fun play() {
        if (_page.value?.kind == WebPage.Kind.YOUTUBE) yt?.play() else js("play()")
    }

    override fun pause() {
        if (_page.value?.kind == WebPage.Kind.YOUTUBE) yt?.pause() else js("pause()")
    }

    override fun seek(ms: Long) {
        if (_page.value?.kind == WebPage.Kind.YOUTUBE) yt?.seekTo(ms / 1000f) else js("seek(${ms / 1000.0})")
    }

    override fun release() {
        runCatching { youtube?.release() }
        runCatching { web?.destroy() }
        youtube = null
        web = null
    }
}

@Composable
actual fun rememberWebPlayer(): WebPlayer? {
    val context = LocalContext.current
    val player = remember { AndroidWebPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

@Composable
actual fun WebPlayerSurface(player: WebPlayer, modifier: Modifier) {
    val p = player as? AndroidWebPlayer ?: return
    val page by p.page.collectAsState()
    when (page?.kind) {
        null -> Unit
        WebPage.Kind.YOUTUBE -> AndroidView(factory = { p.youtubeView() }, modifier = modifier)
        else -> AndroidView(factory = { p.webView() }, modifier = modifier)
    }
}
