package io.tima.core.media

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
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
    /**
     * [glass] — полупрозрачное перо: все такие следы ложатся **одним** полупрозрачным слоем, как закрашено, —
     * пересечения не темнеют (заказчик 2026-10-22: «чтобы он создавал только один слой — как закрасить»).
     */
    data class Brush(val points: List<Offset>, val color: Color, val width: Float, val glass: Boolean = false) : PhotoMark
    data class Arrow(val from: Offset, val to: Offset, val color: Color, val width: Float) : PhotoMark

    /** Ластик (заказчик 2026-10-22): стирает нарисованное до него — кисть, стрелку, маркер; фото не трогает. */
    data class Erase(val points: List<Offset>, val width: Float) : PhotoMark

    /**
     * Надпись (заказчик 2026-10-22). [rect] — рамка в долях исходного фото; [turn] — четверти поворота
     * стопки, при которых её поставили: в том виде она стоит прямо, дальше поворачивается вместе с фото.
     * [background] — заливка рамки со скруглёнными углами, как у пузыря; `null` — без фона. Текст — по левому
     * краю, размер букв — самый крупный, при котором он помещается в рамку. Своим слоем, выше рисунков:
     * ластик надписи не трогает.
     */
    data class Text(
        val rect: Rect,
        val text: String,
        val color: Color,
        val background: Color?,
        val face: TextFace,
        val turn: Int,
    ) : PhotoMark
}

/** Начертание надписи. */
enum class TextFace { Normal, Bold, Italic }

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
    val INKS: List<Color> = listOf(
        Color(0xFFE53935), Color(0xFFFFD600), Color(0xFF43A047), Color(0xFF1E88E5), Color.White, Color.Black,
        // Наш зелёный — шапки приложения (заказчик 2026-10-22: «наш цвет в конце — зелёный из шапки»).
        Color(0xFF8AC44A),
    )

    /** Прозрачность полупрозрачного слоя: фото под ним видно. */
    const val GLASS_ALPHA = 0.4f

    /**
     * Подложка области набора надписи без своего фона: серый средней яркости — на нём читается любой цвет
     * палитры, и белый, и чёрный (заказчик 2026-10-22).
     */
    val TYPING_BACK: Color = Color(0xFF808080)

    private val FULL = Rect(0f, 0f, 1f, 1f)

    /** Размер итога в точках фото [w]×[h]: рамка, а при нечётном повороте стороны меняются местами. */
    fun outSize(w: Int, h: Int, m: PhotoMarks): IntSize {
        val c = m.crop ?: FULL
        val cw = (c.width * w).roundToInt().coerceAtLeast(1)
        val ch = (c.height * h).roundToInt().coerceAtLeast(1)
        return if (m.quarter % 2 == 1) IntSize(ch, cw) else IntSize(cw, ch)
    }

    /**
     * Свести: фото, рамка, поворот, отметки — в одну картинку размером [outSize]. [measurer] — для надписей;
     * без него надписи не рисуются.
     */
    fun render(source: ImageBitmap, m: PhotoMarks, measurer: TextMeasurer? = null): ImageBitmap {
        val size = outSize(source.width, source.height, m)
        val out = ImageBitmap(size.width, size.height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(out), Size(size.width.toFloat(), size.height.toFloat())) {
            drawPhotoLayers(source, m, measurer = measurer)
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

    /** Обратно: доля фото → доля видимого. */
    fun toVisible(p: Offset, quarter: Int, crop: Rect?): Offset {
        val c = rotate(crop ?: FULL, quarter)
        val r = rotate(p, quarter)
        return Offset((r.x - c.left) / c.width, (r.y - c.top) / c.height)
    }

    /** Рамка надписи: видимое ↔ фото. */
    fun rectToVisible(r: Rect, quarter: Int, crop: Rect?): Rect = corners(r).map { toVisible(it, quarter, crop) }.bounds()

    fun rectToPhoto(r: Rect, quarter: Int, crop: Rect?): Rect = corners(r).map { toPhoto(it, quarter, crop) }.bounds()

    /**
     * Надпись в рамке: самый крупный размер букв, при котором текст целиком помещается в [maxW]×[maxH]
     * точек (переносы — по ширине). Пустой текст — `null`.
     */
    fun fitText(measurer: TextMeasurer, t: PhotoMark.Text, maxW: Float, maxH: Float): TextLayoutResult? {
        if (t.text.isBlank() || maxW < 1f || maxH < 1f) return null
        fun measure(size: Float) = measurer.measure(
            AnnotatedString(t.text),
            style = TextStyle(
                color = t.color,
                fontSize = size.sp,
                lineHeight = (size * 1.2f).sp,
                fontWeight = if (t.face == TextFace.Bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (t.face == TextFace.Italic) FontStyle.Italic else FontStyle.Normal,
            ),
            constraints = Constraints(maxWidth = maxW.toInt().coerceAtLeast(1)),
            density = Density(1f),
        )
        var lo = 1f
        var hi = maxH
        var best: TextLayoutResult? = null
        repeat(FIT_STEPS) {
            val mid = (lo + hi) / 2
            val l = measure(mid)
            if (l.size.height <= maxH && !l.didOverflowWidth) {
                best = l
                lo = mid
            } else {
                hi = mid
            }
        }
        return best ?: measure(lo)
    }

    /** Надпись: рамка с заливкой и текст — в системе точек фото, прямо в виде, где её ставили. */
    internal fun DrawScope.text(t: PhotoMark.Text, w: Float, h: Float, measurer: TextMeasurer) {
        val r = Rect(t.rect.left * w, t.rect.top * h, t.rect.right * w, t.rect.bottom * h)
        val q = ((t.turn % 4) + 4) % 4
        val bw = if (q % 2 == 1) r.height else r.width
        val bh = if (q % 2 == 1) r.width else r.height
        val box = Rect(r.center.x - bw / 2, r.center.y - bh / 2, r.center.x + bw / 2, r.center.y + bh / 2)
        // Фото в виде надписи повёрнуто на turn четвертей по часовой — в системе фото надпись повёрнута обратно.
        rotate(-90f * q, pivot = r.center) {
            t.background?.let { drawRoundRect(it, box.topLeft, box.size, CornerRadius(kotlin.math.min(bw, bh) * CORNER)) }
            val pad = kotlin.math.min(bw, bh) * PAD
            val layout = fitText(measurer, t, bw - 2 * pad, bh - 2 * pad) ?: return@rotate
            drawText(layout, topLeft = Offset(box.left + pad, box.top + pad))
        }
    }

    private const val FIT_STEPS = 14
    private const val CORNER = 0.18f
    private const val PAD = 0.08f

    private fun corners(r: Rect) = listOf(r.topLeft, r.topRight, r.bottomRight, r.bottomLeft)

    private fun List<Offset>.bounds() = Rect(minOf { it.x }, minOf { it.y }, maxOf { it.x }, maxOf { it.y })

    internal fun DrawScope.mark(mark: PhotoMark, w: Float, h: Float) {
        val px = (when (mark) {
            is PhotoMark.Brush -> mark.width
            is PhotoMark.Arrow -> mark.width
            is PhotoMark.Erase -> mark.width
            is PhotoMark.Text -> 0f
        } * max(w, h)).coerceAtLeast(1f)
        when (mark) {
            // Внутри полупрозрачного слоя цвет сплошной: прозрачность — у слоя целиком.
            is PhotoMark.Brush -> trail(mark.points, if (mark.glass) mark.color.copy(alpha = 1f) else mark.color, px, w, h, BlendMode.SrcOver)
            is PhotoMark.Erase -> trail(mark.points, Color.Black, px, w, h, BlendMode.Clear)
            // Надписи — своим слоем выше рисунков (drawPhotoLayers), здесь не рисуются.
            is PhotoMark.Text -> Unit
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

    private fun DrawScope.trail(points: List<Offset>, color: Color, px: Float, w: Float, h: Float, blend: BlendMode) {
        if (points.size == 1) {
            val p = points.single()
            drawCircle(color, px / 2, Offset(p.x * w, p.y * h), blendMode = blend)
        } else if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
            drawPath(path, color, style = Stroke(px, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blend)
        }
    }

    private const val HEAD_ANGLE = 0.49f
}

/** Отдельный слой поверх уже нарисованного: внутри — свои отметки и ластик, потом — с прозрачностью [alpha]. */
private inline fun DrawScope.layer(bounds: Rect, alpha: Float, block: DrawScope.() -> Unit) {
    drawContext.canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
    block()
    drawContext.canvas.restore()
}

/**
 * Стопка в системе точек итога: точка итога = точка фото. Рамка вырезает, поворот разворачивает, фото и
 * отметки рисуются под ними; [extra] — ещё не выполненные отметки (редактор показывает их поверх).
 * Масштаб под экран — снаружи (`scale`), здесь его нет: на экране и при сведении рисуется одно и то же.
 */
fun DrawScope.drawPhotoLayers(source: ImageBitmap, m: PhotoMarks, extra: List<PhotoMark> = emptyList(), measurer: TextMeasurer? = null) {
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
            // Отметки — своими слоями над фото: ластик стирает в слое, а не фото. Полупрозрачные — одним слоем
            // ниже сплошных: пересечения не темнеют, сплошное ложится поверх маркера. Ластик действует на
            // оба слоя — на то, что нарисовано до него.
            val all = m.marks + extra
            val bounds = Rect(0f, 0f, w, h)
            with(PhotoLayers) {
                if (all.any { it is PhotoMark.Brush && it.glass }) {
                    layer(bounds, PhotoLayers.GLASS_ALPHA) {
                        for (mk in all) if ((mk is PhotoMark.Brush && mk.glass) || mk is PhotoMark.Erase) mark(mk, w, h)
                    }
                }
                layer(bounds, 1f) {
                    for (mk in all) if (!(mk is PhotoMark.Brush && mk.glass) && mk !is PhotoMark.Text) mark(mk, w, h)
                }
                // Надписи — верхним слоем: ластик до них не достаёт.
                if (measurer != null) for (mk in all) if (mk is PhotoMark.Text) text(mk, w, h, measurer)
            }
        }
    }
}

