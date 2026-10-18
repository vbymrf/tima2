package io.tima.core.media

import androidx.compose.runtime.Composable

/** ПК: системного «Поделиться» нет — «Сохранить как». */
@Composable
actual fun rememberShareImage(): ((bytes: ByteArray, mime: String) -> Boolean)? = null
