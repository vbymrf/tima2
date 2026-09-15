package io.tima.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

actual fun decodeImage(bytes: ByteArray): ImageBitmap? = try {
    // Большие фотографии с камеры — 12 мегапикселей и больше — на телефоне не нужны
    // целиком: обрезка всё равно уйдёт в 512×512. Уменьшаем при чтении, иначе
    // раскодированная картинка займёт 50 МБ, и на слабом телефоне это конец процесса.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
} catch (_: Throwable) {
    null
}

actual fun encodeJpeg(image: ImageBitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/** Дальше этой стороны раскодировать незачем: обрезка уходит в 512. */
private const val MAX_SIDE = 2048
