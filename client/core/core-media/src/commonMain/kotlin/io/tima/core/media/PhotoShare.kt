package io.tima.core.media

import androidx.compose.runtime.Composable

/**
 * Отдать картинку наружу системным «Поделиться» (просмотр фото, заказчик 2026-10-18). `null` —
 * платформа не умеет (ПК): фото сохраняют и отправляют файлом.
 */
@Composable
expect fun rememberShareImage(): ((bytes: ByteArray, mime: String) -> Boolean)?
