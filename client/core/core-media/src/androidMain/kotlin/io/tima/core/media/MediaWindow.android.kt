package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Телефон: окно во весь экран; приложение поворачивается само — видео ложится по горизонтали. */
@Composable
actual fun MediaWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) { content() }
}
