package io.tima.core.media

import androidx.compose.runtime.Composable

@Composable
actual fun rememberShareImage(): ((bytes: ByteArray, mime: String) -> Boolean)? = null
