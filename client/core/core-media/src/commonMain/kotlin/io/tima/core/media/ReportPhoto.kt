package io.tima.core.media

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Снимок к отчёту о проблеме — сжатым (ПЛАН-ВИДЕО.md В6): длинная сторона до
 * [REPORT_SIDE], JPEG до [REPORT_BYTES]. Сервер берёт до мегабайта на снимок; фото с
 * телефона весит пять-десять, и отправлять его как есть значило бы упираться в предел и
 * ждать на плохой связи ровно тогда, когда человек жалуется.
 *
 * `null` — это не картинка.
 */
fun reportJpeg(bytes: ByteArray): ByteArray? = decodeImage(bytes)?.let { reportJpeg(it) }

fun reportJpeg(image: ImageBitmap): ByteArray {
    val scaled = shrink(image, REPORT_SIDE)
    var quality = FIRST_QUALITY
    var out = encodeJpeg(scaled, quality)
    while (out.size > REPORT_BYTES && quality > LAST_QUALITY) {
        quality -= QUALITY_STEP
        out = encodeJpeg(scaled, quality)
    }
    return out
}

/** Уменьшить так, чтобы длинная сторона стала не больше [longest]. Меньшее не трогаем. */
fun shrink(image: ImageBitmap, longest: Int): ImageBitmap {
    val side = max(image.width, image.height)
    if (side <= longest) return image
    val scale = longest.toFloat() / side
    val w = (image.width * scale).roundToInt().coerceAtLeast(1)
    val h = (image.height * scale).roundToInt().coerceAtLeast(1)
    val out = ImageBitmap(w, h)
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(out),
        androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()),
    ) {
        drawImage(image = image, dstSize = IntSize(w, h))
    }
    return out
}

/** Длинная сторона снимка к отчёту: полосы и рябь на ней ещё видны, вес — уже нет. */
const val REPORT_SIDE = 1600

/** Целевой вес снимка, байт. */
const val REPORT_BYTES = 500_000

private const val FIRST_QUALITY = 80
private const val LAST_QUALITY = 35
private const val QUALITY_STEP = 15
