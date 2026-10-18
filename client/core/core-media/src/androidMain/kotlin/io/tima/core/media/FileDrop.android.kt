package io.tima.core.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Телефон: файлы в окно не перетаскивают — выбирают Скрепкой. */
@Composable
actual fun Modifier.fileDrop(onHover: (Boolean) -> Unit, onFiles: (List<PickedFile>) -> Unit): Modifier = this
