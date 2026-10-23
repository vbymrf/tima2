package io.tima.core.media

import androidx.compose.runtime.Composable

/** На iOS приложения пока нет. */
@Composable
actual fun MediaWindow(title: String, onClose: () -> Unit, content: @Composable () -> Unit) = content()

@Composable
actual fun KeepScreenOn(on: Boolean) = Unit
