package io.tima.core.media

import androidx.compose.ui.graphics.ImageBitmap

/** Раскодировать картинку из файла. `null` — не картинка или битая. */
expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/**
 * Раскодировать под место показа: стороны не меньше [atLeast] пикселей, но и не больше,
 * чем нужно, — уменьшение в два, четыре, восемь раз прямо при чтении (ПЛАН-(БС) Э5.5).
 * Аватар 512×512 в строке списка шириной 84 точки занимал бы мегабайт памяти вместо 30 КБ.
 * Где уменьшать при чтении нечем (ПК), — как [decodeImage].
 */
expect fun decodeImage(bytes: ByteArray, atLeast: Int): ImageBitmap?

/**
 * Закодировать в JPEG.
 *
 * JPEG, а не PNG: аватар — фотография, и PNG весил бы в разы больше без выигрыша.
 * Прозрачности у аватара нет — он всегда квадрат на подложке.
 */
expect fun encodeJpeg(image: ImageBitmap, quality: Int = 85): ByteArray
