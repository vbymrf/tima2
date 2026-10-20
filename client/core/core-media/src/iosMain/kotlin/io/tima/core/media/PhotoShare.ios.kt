package io.tima.core.media

import androidx.compose.runtime.Composable

@Composable
actual fun rememberShareImage(): ((bytes: ByteArray, mime: String) -> Boolean)? = null

@Composable
actual fun rememberShareFile(): ((bytes: ByteArray, mime: String, name: String) -> Boolean)? = null

@Composable
actual fun rememberOpenFile(): ((bytes: ByteArray, mime: String, name: String) -> Boolean)? = null
