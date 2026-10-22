package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Фото к отправке (ПЛАН-(ПФ) ПФ3, Решения §3): большая сторона 1280 — или сколько назвал сервер
 * (ПЛАН-(ИФ) ИФ2). Превью нет (ADR-0052): фото уходит целиком, превью делает получатель.
 */
object PhotoPrep {
    const val FULL_SIDE = 1280

    /** Под место внутри сообщения: ряд целиком — 720 (Решения §3). */
    const val INLINE_SIDE = 720

    class Prepared(val full: ByteArray, val width: Int, val height: Int)

    /** Фото по большей стороне [side]; не картинка — `null`. */
    fun prepare(bytes: ByteArray, side: Int = FULL_SIDE): Prepared? {
        val source = decodeImage(bytes, atLeast = side) ?: return null
        return fit(source, side)
    }

    /** Готовая картинка (после редактора фото) — по большей стороне [side], JPEG 85. */
    fun fit(image: ImageBitmap, side: Int = FULL_SIDE): Prepared {
        val full = scaled(image, side)
        return Prepared(encodeJpeg(full, 85), full.width, full.height)
    }

    /**
     * Картинка внутри сообщения (Решения §3): под ширину места — ряд целиком 720, ячейка — её доля;
     * высота по пропорции, превью нет. Не картинка — `null`.
     */
    fun inline(bytes: ByteArray, width: Int = INLINE_SIDE): Prepared? {
        val source = decodeImage(bytes, atLeast = width) ?: return null
        val fitted = if (source.width <= width) source else scaled(source, (maxOf(source.width, source.height) * width.toFloat() / source.width).roundToInt())
        return Prepared(encodeJpeg(fitted, 85), fitted.width, fitted.height)
    }

    /** Большая сторона не больше [side]; меньшее не растягивается. */
    fun scaled(image: ImageBitmap, side: Int): ImageBitmap {
        val longest = max(image.width, image.height)
        if (longest <= side) return image
        val k = side.toFloat() / longest
        val w = (image.width * k).roundToInt().coerceAtLeast(1)
        val h = (image.height * k).roundToInt().coerceAtLeast(1)
        val out = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w.toFloat(), h.toFloat())) {
            drawImage(image, IntOffset.Zero, IntSize(image.width, image.height), IntOffset.Zero, IntSize(w, h))
        }
        return out
    }
}
