package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Шаг правки фото (ПЛАН-(ИФ) ИФ6, заказчик 2026-10-22): поворот, обрезка, кисть, стрелка.
 *
 * Координаты — **доли** картинки, какой она стала к этому шагу (0…1): экран и картинка разного
 * размера, а поворот и обрезка меняют саму картинку. Толщина — доля большей стороны.
 */
sealed interface PhotoStep {
    /** Четверть оборота по часовой. */
    data object Turn : PhotoStep

    data class Crop(val left: Float, val top: Float, val right: Float, val bottom: Float) : PhotoStep

    data class Brush(val points: List<Offset>, val color: Color, val width: Float) : PhotoStep

    data class Arrow(val from: Offset, val to: Offset, val color: Color, val width: Float) : PhotoStep
}

/**
 * Шаги поверх исходного — по порядку, каждый в новую картинку. Отмена — снять последний шаг и
 * пройти заново: прежних состояний редактор не хранит (заказчик 2026-10-22: «редактор не хранит
 * предыдущие состояния» — после «Сохранить» остаётся только итог).
 */
object PhotoSteps {

    /**
     * Краска кисти и стрелки: красный, жёлтый, зелёный, синий, белый, чёрный. Не цвета темы — краска
     * ложится в само фото и у получателя выглядит так же при любой теме.
     */
    val INKS: List<Color> = listOf(Color(0xFFE53935), Color(0xFFFFD600), Color(0xFF43A047), Color(0xFF1E88E5), Color.White, Color.Black)

    fun apply(source: ImageBitmap, steps: List<PhotoStep>): ImageBitmap =
        steps.fold(source) { image, step -> apply(image, step) }

    fun apply(image: ImageBitmap, step: PhotoStep): ImageBitmap = when (step) {
        PhotoStep.Turn -> turn(image)
        is PhotoStep.Crop -> crop(image, step)
        is PhotoStep.Brush -> drawn(image) { w, h ->
            if (step.points.size == 1) {
                val p = step.points.single()
                drawCircle(step.color, stroke(step.width, w, h) / 2, Offset(p.x * w, p.y * h))
            } else if (step.points.size > 1) {
                val path = Path()
                step.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
                drawPath(path, step.color, style = Stroke(stroke(step.width, w, h), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        is PhotoStep.Arrow -> drawn(image) { w, h -> arrow(step, w, h) }
    }

    /** Четверть оборота по часовой: ширина и высота меняются местами. */
    fun turn(image: ImageBitmap): ImageBitmap {
        val out = ImageBitmap(image.height, image.width)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(out.width.toFloat(), out.height.toFloat())) {
            // Поворот вокруг начала уводит картинку влево за край — сдвиг на новую ширину её возвращает.
            translate(left = out.width.toFloat()) {
                rotate(90f, pivot = Offset.Zero) { drawImage(image) }
            }
        }
        return out
    }

    fun crop(image: ImageBitmap, c: PhotoStep.Crop): ImageBitmap {
        val l = (minOf(c.left, c.right).coerceIn(0f, 1f) * image.width).roundToInt()
        val t = (minOf(c.top, c.bottom).coerceIn(0f, 1f) * image.height).roundToInt()
        val r = (max(c.left, c.right).coerceIn(0f, 1f) * image.width).roundToInt()
        val b = (max(c.top, c.bottom).coerceIn(0f, 1f) * image.height).roundToInt()
        val w = (r - l).coerceAtLeast(1)
        val h = (b - t).coerceAtLeast(1)
        val out = ImageBitmap(w, h)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w.toFloat(), h.toFloat())) {
            drawImage(image, IntOffset(l, t), IntSize(w, h), IntOffset.Zero, IntSize(w, h))
        }
        return out
    }

    private fun drawn(image: ImageBitmap, block: DrawScope.(w: Float, h: Float) -> Unit): ImageBitmap {
        val out = ImageBitmap(image.width, image.height)
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(w, h)) {
            drawImage(image)
            block(w, h)
        }
        return out
    }

    private fun stroke(width: Float, w: Float, h: Float) = (width * max(w, h)).coerceAtLeast(1f)

    /** Стрелка: линия и два уса у острия под 28° к ней; длина усов — от толщины. */
    private fun DrawScope.arrow(a: PhotoStep.Arrow, w: Float, h: Float) {
        val from = Offset(a.from.x * w, a.from.y * h)
        val to = Offset(a.to.x * w, a.to.y * h)
        val px = stroke(a.width, w, h)
        drawLine(a.color, from, to, px, StrokeCap.Round)
        val angle = atan2(to.y - from.y, to.x - from.x)
        val head = max(px * 4f, max(w, h) * 0.03f)
        for (side in listOf(-1f, 1f)) {
            val back = angle + PI.toFloat() + side * HEAD_ANGLE
            drawLine(a.color, to, Offset(to.x + head * cos(back), to.y + head * sin(back)), px, StrokeCap.Round)
        }
    }

    private const val HEAD_ANGLE = 0.49f
}
