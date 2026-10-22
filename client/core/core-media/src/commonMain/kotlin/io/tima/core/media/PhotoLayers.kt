package io.tima.core.media

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Отметка верхнего слоя (кисть, стрелка). Точки — **доли исходного фото**, до поворота и обрезки;
 * толщина — доля его большей стороны. Поэтому одна и та же отметка ложится одинаково на уменьшенную
 * копию в редакторе и на исходник при сведении.
 */
sealed interface PhotoMark {
    data class Brush(val points: List<Offset>, val color: Color, val width: Float) : PhotoMark
    data class Arrow(val from: Offset, val to: Offset, val color: Color, val width: Float) : PhotoMark
}

/**
 * Правка фото слоями (заказчик 2026-10-22: «наложить сверху наши рисования, как в фотошопе слои… и далее
 * обработка в конце»). Фото не трогается; [turns] — четверти оборота по часовой и [crop] — рамка в долях
 * исходного — действуют на всю стопку; [marks] — свой слой поверх фото. Сводится один раз —
 * [PhotoLayers.render], на исходнике нужного размера: обрезка не превращает фото в «огрызок».
 */
data class PhotoMarks(
    val turns: Int = 0,
    val crop: Rect? = null,
    val marks: List<PhotoMark> = emptyList(),
) {
    /** Четверти оборота, приведённые к 0…3. */
    val quarter: Int get() = ((turns % 4) + 4) % 4

    /** Ничего не изменено — отправлять можно исходник как есть. */
    val isEmpty: Boolean get() = quarter == 0 && crop == null && marks.isEmpty()
}

/** Сведение слоёв и перевод точек между фото и тем, что видно (повёрнуто и обрезано). */
object PhotoLayers {

    /**
     * Краска кисти и стрелки: красный, жёлтый, зелёный, синий, белый, чёрный. Не цвета темы — краска
     * ложится в само фото и у получателя выглядит так же при любой теме.
     */
    val INKS: List<Color> = listOf(Color(0xFFE53935), Color(0xFFFFD600), Color(0xFF43A047), Color(0xFF1E88E5), Color.White, Color.Black)

    private val FULL = Rect(0f, 0f, 1f, 1f)

    /** Размер итога в точках фото [w]×[h]: рамка, а при нечётном повороте стороны меняются местами. */
    fun outSize(w: Int, h: Int, m: PhotoMarks): IntSize {
        val c = m.crop ?: FULL
        val cw = (c.width * w).roundToInt().coerceAtLeast(1)
        val ch = (c.height * h).roundToInt().coerceAtLeast(1)
        return if (m.quarter % 2 == 1) IntSize(ch, cw) else IntSize(cw, ch)
    }

    /** Свести: фото, рамка, поворот, отметки — в одну картинку размером [outSize]. */
    fun render(source: ImageBitmap, m: PhotoMarks): ImageBitmap {
        val size = outSize(source.width, source.height, m)
        val out = ImageBitmap(size.width, size.height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(size.width.toFloat(), size.height.toFloat())) {
            drawPhotoLayers(source, m)
        }
        return out
    }

    /** Четверть оборота по часовой — поворот кнопкой просмотра. */
    fun turn(image: ImageBitmap): ImageBitmap = render(image, PhotoMarks(turns = 1))

    // ── Перевод долей: фото ↔ повёрнутое ↔ видимое ─────────────────────────────

    /** Точка фото → точка повёрнутого на [quarter] четвертей (доли). */
    fun rotate(p: Offset, quarter: Int): Offset = when (((quarter % 4) + 4) % 4) {
        1 -> Offset(1f - p.y, p.x)
        2 -> Offset(1f - p.x, 1f - p.y)
        3 -> Offset(p.y, 1f - p.x)
        else -> p
    }

    /** Обратно: точка повёрнутого → точка фото. */
    fun unrotate(p: Offset, quarter: Int): Offset = when (((quarter % 4) + 4) % 4) {
        1 -> Offset(p.y, 1f - p.x)
        2 -> Offset(1f - p.x, 1f - p.y)
        3 -> Offset(1f - p.y, p.x)
        else -> p
    }

    fun rotate(r: Rect, quarter: Int): Rect = corners(r).map { rotate(it, quarter) }.bounds()

    fun unrotate(r: Rect, quarter: Int): Rect = corners(r).map { unrotate(it, quarter) }.bounds()

    /** Доля видимого (повёрнутого и обрезанного) → доля фото. Вне видимого — вне фото: следу это можно. */
    fun toPhoto(q: Offset, quarter: Int, crop: Rect?): Offset {
        val c = rotate(crop ?: FULL, quarter)
        return unrotate(Offset(c.left + q.x * c.width, c.top + q.y * c.height), quarter)
    }

    private fun corners(r: Rect) = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)

    private fun List<Offset>.bounds() = Rect(minOf { it.x }, minOf { it.y }, maxOf { it.x }, maxOf { it.y })

    internal fun DrawScope.mark(mark: PhotoMark, w: Float, h: Float) {
        val px = (when (mark) {
            is PhotoMark.Brush -> mark.width
            is PhotoMark.Arrow -> mark.width
        } * max(w, h)).coerceAtLeast(1f)
        when (mark) {
            is PhotoMark.Brush -> if (mark.points.size == 1) {
                val p = mark.points.single()
                drawCircle(mark.color, px / 2, Offset(p.x * w, p.y * h))
            } else if (mark.points.size > 1) {
                val path = Path()
                mark.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
                drawPath(path, mark.color, style = Stroke(px, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            is PhotoMark.Arrow -> {
                val from = Offset(mark.from.x * w, mark.from.y * h)
                val to = Offset(mark.to.x * w, mark.to.y * h)
                drawLine(mark.color, from, to, px, StrokeCap.Round)
                val angle = atan2(to.y - from.y, to.x - from.x)
                val head = max(px * 4f, max(w, h) * 0.03f)
                for (side in listOf(-1f, 1f)) {
                    val back = angle + PI.toFloat() + side * HEAD_ANGLE
                    drawLine(mark.color, to, Offset(to.x + head * cos(back), to.y + head * sin(back)), px, StrokeCap.Round)
                }
            }
        }
    }

    private const val HEAD_ANGLE = 0.49f
}

/**
 * Стопка в системе точек итога: точка итога = точка фото. Рамка вырезает, поворот разворачивает, фото и
 * отметки рисуются под ними; [extra] — ещё не выполненные отметки (редактор показывает их поверх).
 * Масштаб под экран — снаружи (`scale`), здесь его нет: на экране и при сведении рисуется одно и то же.
 */
fun DrawScope.drawPhotoLayers(source: ImageBitmap, m: PhotoMarks, extra: List<PhotoMark> = emptyList()) {
    val w = source.width.toFloat()
    val h = source.height.toFloat()
    val c = m.crop ?: Rect(0f, 0f, 1f, 1f)
    val cw = c.width * w
    val ch = c.height * h
    val odd = m.quarter % 2 == 1
    clipRect(0f, 0f, if (odd) ch else cw, if (odd) cw else ch) {
        withTransform({
            when (m.quarter) {
                1 -> { translate(ch, 0f); rotate(90f, Offset.Zero) }
                2 -> { translate(cw, ch); rotate(180f, Offset.Zero) }
                3 -> { translate(0f, cw); rotate(270f, Offset.Zero) }
            }
            translate(-c.left * w, -c.top * h)
        }) {
            drawImage(source)
            with(PhotoLayers) {
                for (mk in m.marks) mark(mk, w, h)
                for (mk in extra) mark(mk, w, h)
            }
        }
    }
}

