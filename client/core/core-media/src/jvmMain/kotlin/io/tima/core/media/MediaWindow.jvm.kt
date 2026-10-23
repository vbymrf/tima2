package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState

private val LocalMediaWindowState = staticCompositionLocalOf<WindowState?> { null }

/** ПК: своё окно рядом с основным — браузерный движок в нём ничего не перекрывает. */
@Composable
actual fun MediaWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberWindowState(width = 1000.dp, height = 640.dp, position = WindowPosition.PlatformDefault)
    Window(onCloseRequest = onClose, state = state, title = title) {
        // Хозяин WebView2 — это окно (ПЛАН-(ВМ) ВМ6).
        CompositionLocalProvider(LocalMediaAwtWindow provides window, LocalMediaWindowState provides state) { content() }
    }
}

@Composable
actual fun KeepScreenOn(on: Boolean) = Unit

/** ПК: окно проигрывания во весь экран; поворота нет. */
@Composable
actual fun FullScreenMode(on: Boolean, turn: ScreenTurn, onUpright: () -> Unit) {
    val state = LocalMediaWindowState.current ?: return
    DisposableEffect(state, on) {
        state.placement = if (on) WindowPlacement.Fullscreen else WindowPlacement.Floating
        onDispose { state.placement = WindowPlacement.Floating }
    }
}

actual val turnsWithPhone: Boolean = false
