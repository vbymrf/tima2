package io.tima.core.media

import androidx.compose.runtime.Composable

@Composable
actual fun rememberClipboardHtml(): () -> String? = { null }

@Composable
actual fun rememberClipboardImage(): () -> PickedFile? = { null }
