package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Обрезка квадратом — то, что человек сделал пальцами на экране, переведённое в пиксели.
 *
 * Экран показывает картинку в окне [viewport]×[viewport], сдвинутую на [pan] и
 * увеличенную в [zoom] раз относительно «вписана целиком». Здесь та же геометрия
 * считается назад: какой квадрат исходника виден в окне — и он рисуется в [side]×[side].
 *
 * Считается один раз, при нажатии «Готово», а не на каждое движение пальца: рисовать
 * в новую картинку на каждый кадр — ненужная работа, экран и так показывает то же самое
 * через Modifier.graphicsLayer.
 */
class SquareCrop(
    val image: ImageBitmap,
    /** Сторона окна обрезки на экране, в тех же единицах, что [pan]. */
    val viewport: Float,
) {
    /** Во сколько раз картинка меньше окна, когда вписана целиком по меньшей стороне. */
    val baseScale: Float = viewport / min(image.width, image.height)

    /**
     * Насколько можно сдвинуть при данном [zoom], чтобы окно не вышло за картинку.
     * Возвращает пределы по x и y; нулевые — двигать некуда.
     */
    fun panLimit(zoom: Float): Offset {
        val shownW = image.width * baseScale * zoom
        val shownH = image.height * baseScale * zoom
        return Offset(max(0f, (shownW - viewport) / 2), max(0f, (shownH - viewport) / 2))
    }

    /** Сдвиг, прижатый к пределам: за край картинки окно не уходит. */
    fun clampPan(pan: Offset, zoom: Float): Offset {
        val lim = panLimit(zoom)
        return Offset(pan.x.coerceIn(-lim.x, lim.x), pan.y.coerceIn(-lim.y, lim.y))
    }

    /** Вырезать то, что видно в окне, в квадрат [side]×[side]. */
    fun cut(pan: Offset, zoom: Float, side: Int = OUTPUT_SIDE): ImageBitmap {
        val z = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val p = clampPan(pan, z)
        val scale = baseScale * z
        // Левый верхний угол окна в пикселях исходника. Картинка центрирована в окне и
        // сдвинута на pan, значит окно в её координатах сдвинуто на -pan.
        val shownW = image.width * scale
        val shownH = image.height * scale
        val left = ((shownW - viewport) / 2 - p.x) / scale
        val top = ((shownH - viewport) / 2 - p.y) / scale
        val size = viewport / scale

        val out = ImageBitmap(side, side)
        val canvas = Canvas(out)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, androidx.compose.ui.geometry.Size(side.toFloat(), side.toFloat())) {
            drawImage(
                image = image,
                srcOffset = IntOffset(left.roundToInt().coerceIn(0, image.width - 1), top.roundToInt().coerceIn(0, image.height - 1)),
                srcSize = IntSize(size.roundToInt().coerceIn(1, image.width), size.roundToInt().coerceIn(1, image.height)),
                dstSize = IntSize(side, side),
            )
        }
        return out
    }

    companion object {
        /** Сторона аватара, который уходит на сервер. 512 — с запасом на любой экран списка. */
        const val OUTPUT_SIDE = 512
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 6f
    }
}
