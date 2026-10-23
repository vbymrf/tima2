package io.tima.core.media

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.sun.jna.Native
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Окно AWT окна проигрывания — хозяин WebView2; ставит [MediaWindow] на ПК. */
internal val LocalMediaAwtWindow = staticCompositionLocalOf<java.awt.Window?> { null }

/**
 * Браузерный движок на ПК (ПЛАН-(ВМ) ВМ6, Р15): WebView2 в окне проигрывания — дочерним окном на
 * месте картинки. Страница и мост — общие ([WebPages]); YouTube — та же страница IFrame API.
 */
internal class WindowsWebPlayer : WebPlayer {
    private val _state = MutableStateFlow(OwnPlayerState())
    override val state: StateFlow<OwnPlayerState> = _state.asStateFlow()
    private var engine: WebView2? = null
    private var pending: WebPage? = null
    private var startSec = 0L

    /** Хозяин появился — поднять WebView2 и открыть отложенное. */
    fun attach(window: java.awt.Window) {
        if (engine != null) return
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return run {
            _state.value = _state.value.copy(error = "нет окна-хозяина")
        }
        val base = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"), "TIMA/webview2")
        engine = WebView2(File(base, "data"), File(base, "pages")).also { e ->
            e.onMessage = { m -> _state.value = _state.value.bridged(m) }
            e.onFailure = { why -> _state.value = _state.value.copy(error = why, playing = false) }
            e.start(hwnd)
            pending?.let { e.navigate(it.html) }
        }
    }

    fun bounds(x: Int, y: Int, w: Int, h: Int) {
        engine?.bounds(x, y, w, h)
    }

    override fun open(page: WebPage, startMs: Long) {
        _state.value = OwnPlayerState()
        pending = page
        startSec = startMs / 1000
        engine?.navigate(page.html)
    }

    private fun js(code: String) = engine?.script("window.tima&&tima.$code")

    override fun play() {
        js("play()")
    }

    override fun pause() {
        js("pause()")
    }

    override fun seek(ms: Long) {
        js("seek(${ms / 1000.0})")
    }

    override fun setMuted(muted: Boolean) {
        js("mute($muted)")
    }

    override fun release() {
        engine?.close()
        engine = null
    }
}

private val onWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

@Composable
actual fun rememberWebPlayer(): WebPlayer? {
    if (!onWindows) return null
    val player = remember { WindowsWebPlayer() }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

@Composable
actual fun WebPlayerSurface(player: WebPlayer, modifier: Modifier) {
    val p = player as? WindowsWebPlayer ?: return
    val window = LocalMediaAwtWindow.current ?: return
    DisposableEffect(window) {
        p.attach(window)
        onDispose { }
    }
    Box(
        modifier.onGloballyPositioned { c ->
            val at = c.positionInWindow()
            p.bounds(at.x.toInt(), at.y.toInt(), c.size.width, c.size.height)
        },
    )
}
