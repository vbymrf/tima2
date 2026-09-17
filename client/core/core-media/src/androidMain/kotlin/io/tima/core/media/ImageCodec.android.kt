package io.tima.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
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
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?.let { upright(it, bytes) }
        ?.asImageBitmap()
} catch (_: Throwable) {
    null
}

actual fun encodeJpeg(image: ImageBitmap, quality: Int): ByteArray {
    val out = ByteArrayOutputStream()
    image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, quality, out)
    return out.toByteArray()
}

/**
 * Поставить снимок как надо: по метке поворота, которую пишет камера.
 *
 * **`BitmapFactory` эту метку не смотрит вовсе.** Телефон снимает матрицей в одном
 * положении всегда, а как держали аппарат, записывает отдельным полем EXIF `Orientation`.
 * Галерея его читает — поэтому в галерее фотография стоит прямо, — а мы читали одни
 * пиксели и показывали снимок лёжа.
 *
 * Поймано на профиле 2026-09-17: поставленная фотография оказывалась повёрнутой на 90°, и
 * выглядело это как беда нашей обрезки. Обрезка была ни при чём.
 *
 * Отражения (`FLIP_*`) обрабатываются тоже: их даёт фронтальная камера и правка в чужом
 * редакторе, и обойдённое отражение — это зеркальное лицо, которое человек заметит сразу.
 */
private fun upright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
    val orientation = try {
        ExifInterface(ByteArrayInputStream(bytes))
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: Throwable) {
        // Метки нет или её не прочитать — обычное дело для PNG и снимков экрана.
        return bitmap
    }
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (_: Throwable) {
        // Памяти не хватило — лучше боком, чем никак.
        bitmap
    }
}

/** Дальше этой стороны раскодировать незачем: обрезка уходит в 512. */
private const val MAX_SIDE = 2048
