package io.tima.core.media

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Телефон: окно во весь экран; приложение поворачивается само — видео ложится по горизонтали. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun MediaWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Окно — своё, вне корня `MainActivity`: метки `player:*` для сценариев включаются здесь заново,
        // иначе в дереве экрана их нет (2026-10-23).
        Box(Modifier.semantics { testTagsAsResourceId = true }) { content() }
    }
}
