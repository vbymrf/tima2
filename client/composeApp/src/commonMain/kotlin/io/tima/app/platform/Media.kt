package io.tima.app.platform

import androidx.compose.ui.graphics.ImageBitmap

class PickedImage(val bytes: ByteArray, val mime: String)

/** Системный выбор изображения; null — пользователь передумал. */
expect suspend fun pickImage(): PickedImage?

/** Байты (jpg/png/webp) → картинка; null — не декодируется. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
