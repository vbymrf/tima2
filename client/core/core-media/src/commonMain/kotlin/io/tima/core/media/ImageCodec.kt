package io.tima.core.media

import androidx.compose.ui.graphics.ImageBitmap

/** Раскодировать картинку из файла. `null` — не картинка или битая. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/**
 * Закодировать в JPEG.
 *
 * JPEG, а не PNG: аватар — фотография, и PNG весил бы в разы больше без выигрыша.
 * Прозрачности у аватара нет — он всегда квадрат на подложке.
 */
expect fun encodeJpeg(image: ImageBitmap, quality: Int = 85): ByteArray
