package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState

/** ПК: своё окно рядом с основным — браузерный движок в нём ничего не перекрывает. */
@Composable
actual fun MediaWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberWindowState(width = 1000.dp, height = 640.dp, position = WindowPosition.PlatformDefault)
    Window(onCloseRequest = onClose, state = state, title = title) { content() }
}
