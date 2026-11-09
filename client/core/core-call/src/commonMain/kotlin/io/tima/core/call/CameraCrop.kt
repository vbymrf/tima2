package io.tima.core.call

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Режим адаптации изображения (ПЛАН-(ЗН) ЗН5, заказчик 2026-11-10): **своё** изображение
 * приближается обрезкой кадра камеры перед отправкой — собеседник видит то же, что своё окошко.
 *
 * [zoom] — ступень из [STEPS]; [x], [y] — середина видимой части относительно середины кадра,
 * долями ширины и высоты кадра, как его видит человек (уже повёрнутого): вправо и вниз —
 * положительные.
 *
 * **Сдвиг выходит за край кадра** (заказчик 2026-11-10, вариант «а»): до края ход при малом приближении короток —
 * при 1,3× всего 11 % кадра. За краем — чёрное поле, и его видит собеседник. Середина видимой части доходит до края
 * кадра ([MAX_SHIFT]): хотя бы половина видимого остаётся картинкой. Сдвигать можно и при 1×.
 */
data class CameraCrop(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    /** Весь кадр как есть: не приближено и не сдвинуто — обрезать нечего. */
    val full: Boolean get() = zoom <= 1f + EPS && abs(x) < EPS && abs(y) < EPS

    /** Насколько середина может отойти от середины кадра, не показав края: видна 1/zoom, остальное — поровну. */
    private val reach: Float get() = (1f - 1f / zoom) / 2f

    fun clamped(): CameraCrop {
        val c = copy(zoom = zoom.coerceIn(1f, STEPS.last()), x = x.coerceIn(-MAX_SHIFT, MAX_SHIFT), y = y.coerceIn(-MAX_SHIFT, MAX_SHIFT))
        return if (c.full) FULL else c
    }

    /** «+»: следующая ступень, сдвиг сохраняется в пределах нового края. */
    fun closer(): CameraCrop = copy(zoom = STEPS.firstOrNull { it > zoom + EPS } ?: zoom).clamped()

    /** «−»: прежняя ступень, сдвиг остаётся. */
    fun farther(): CameraCrop = copy(zoom = STEPS.lastOrNull { it < zoom - EPS } ?: 1f).clamped()

    /** Стрелки: шаг — 1/20 видимой части (заказчик 2026-11-10: в 5 раз мельче четверти); [dx], [dy] — −1, 0 или 1. */
    fun moved(dx: Int, dy: Int): CameraCrop = copy(x = x + dx * step, y = y + dy * step).clamped()

    private val step: Float get() = 1f / zoom / PAN_PARTS

    val canCloser: Boolean get() = STEPS.any { it > zoom + EPS }
    val canFarther: Boolean get() = zoom > 1f + EPS
    fun canMove(dx: Int, dy: Int): Boolean =
        (dx < 0 && x > -MAX_SHIFT + EPS) || (dx > 0 && x < MAX_SHIFT - EPS) || (dy < 0 && y > -MAX_SHIFT + EPS) || (dy > 0 && y < MAX_SHIFT - EPS)

    /** Сдвиг в процентах кадра для показа: вправо и **вверх** — плюс (заказчик 2026-11-10: «+11 −25»). */
    val shiftPercent: Pair<Int, Int> get() = (x * 100).roundToInt() to (-y * 100).roundToInt()

    /** На сколько видимая часть ушла за край кадра, в процентах кадра; знак — та же сторона, что у сдвига. */
    val overPercent: Pair<Int, Int>
        get() {
            fun over(v: Float) = (max(0f, abs(v) - reach) * 100 * (if (v < 0) -1 else 1)).roundToInt()
            return over(x) to over(-y)
        }

    /**
     * Как обрезка выглядит в зеркале своего окошка: сдвиг по горизонтали — в другую сторону. Стрелки и цифры режима
     * адаптации считаются в нём, чтобы «влево» было влево на экране; уходит — прямая.
     */
    fun mirrored(on: Boolean): CameraCrop = if (on) copy(x = -x) else this

    /** Видна ли часть за краем кадра — тогда кадр собирается с полем ([cut]), а не одной обрезкой. */
    val beyond: Boolean get() = abs(x) > reach + EPS || abs(y) > reach + EPS

    /** Для журнала: «2×, сдвиг 0.13 / -0.06». */
    fun describe(): String = "${label(zoom)}×" + if (abs(x) < EPS && abs(y) < EPS) "" else ", сдвиг ${round2(x)} / ${round2(y)}"

    /**
     * Прямоугольник обрезки в кадре [width]×[height], **ещё не повёрнутом** на [rotation]
     * (градусы по часовой, как у WebRTC): сдвиг задан в повёрнутом виде и переводится в оси
     * буфера. Ширина и высота — чётные: так их берут кодеры.
     */
    fun region(width: Int, height: Int, rotation: Int = 0): Region {
        val c = clamped()
        val w = (width / c.zoom).toInt().coerceAtLeast(2) and 1.inv()
        val h = (height / c.zoom).toInt().coerceAtLeast(2) and 1.inv()
        val (bx, by) = turned(c, rotation)
        val left = ((width - w) / 2f + bx * width).toInt().coerceIn(0, width - w) and 1.inv()
        val top = ((height - h) / 2f + by * height).toInt().coerceIn(0, height - h) and 1.inv()
        return Region(left, top, w, h)
    }

    data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    /**
     * Видимая часть, ушедшая за край ([beyond]): [src] — что из кадра [width]×[height] (не повёрнутого) ещё видно,
     * [dst] — куда это ложится в кадре на выход [outW]×[outH]; остальное — поле. Всё чётное.
     */
    fun cut(width: Int, height: Int, rotation: Int = 0, outW: Int = width, outH: Int = height): Cut {
        val c = clamped()
        val w = width / c.zoom
        val h = height / c.zoom
        val (bx, by) = turned(c, rotation)
        val left = (width - w) / 2f + bx * width
        val top = (height - h) / 2f + by * height
        val x0 = max(0f, left)
        val y0 = max(0f, top)
        val x1 = min(width.toFloat(), left + w)
        val y1 = min(height.toFloat(), top + h)
        val sx = outW / w
        val sy = outH / h
        val src = Region(even(x0), even(y0), even(x1 - x0).coerceAtLeast(2), even(y1 - y0).coerceAtLeast(2))
        val dw = even((x1 - x0) * sx).coerceIn(2, outW)
        val dh = even((y1 - y0) * sy).coerceIn(2, outH)
        val dst = Region(even((x0 - left) * sx).coerceIn(0, outW - dw), even((y0 - top) * sy).coerceIn(0, outH - dh), dw, dh)
        return Cut(src.copy(left = src.left.coerceIn(0, width - src.width), top = src.top.coerceIn(0, height - src.height)), dst)
    }

    data class Cut(val src: Region, val dst: Region)

    companion object {
        val FULL = CameraCrop()

        /** Дальше всего середина видимой части отходит до края кадра. */
        const val MAX_SHIFT = 0.5f

        /** Сдвиг экрана → сдвиг буфера: поворот на 90 по часовой кладёт ось y буфера влево. */
        private fun turned(c: CameraCrop, rotation: Int): Pair<Float, Float> = when (((rotation % 360) + 360) % 360) {
            90 -> c.y to -c.x
            180 -> -c.x to -c.y
            270 -> -c.y to c.x
            else -> c.x to c.y
        }

        private fun even(v: Float): Int = v.toInt() and 1.inv()

        /** «+11», «−25», «0» — для показа сдвига. */
        fun signed(v: Int): String = when {
            v > 0 -> "+$v"
            v < 0 -> "\u2212${-v}"
            else -> "0"
        }

        /**
         * Ступени приближения (ответ заказчика 2: до 3×) — через 0,1× (заказчик 2026-11-10: было 1 · 1,5 · 2 · 3,
         * «увеличь количество в 5 раз»): от 1× до 3× — 20 нажатий.
         */
        val STEPS = (10..30).map { it / 10f }

        /** На сколько частей делится видимая часть при сдвиге: было 4. */
        private const val PAN_PARTS = 20f

        private const val EPS = 0.001f

        fun label(zoom: Float): String {
            val tenths = (zoom * 10).let { kotlin.math.round(it).toInt() }
            return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
        }

        private fun round2(v: Float): String = ((v * 100).toInt() / 100f).toString()
    }
}

/** В какой руке держат телефон — куда сдвигать увеличенное изображение (ЗН8). */
enum class CallHand { Right, Left, Both }

/**
 * Вид окна звонка и своё изображение (ПЛАН-(ЗН)). В настройках устройства: у другого телефона свой экран и свой
 * выбор. С 2026-11-10 вид и увеличение — в «Настройки → Звонки», режим адаптации — в «Испытательном режиме звонков».
 */
object CallLookKeys {
    /** `old` — прежний вид окна звонка; нет ключа или иное — новый (ответ заказчика 5: по умолчанию новый). */
    const val LOOK = "call.look"

    /** `1` — режим адаптации изображения включён; по умолчанию выключен. */
    const val ADAPT = "call.adapt"

    /** «Увеличение изображения» (ЗН8): `1`, `1.3`, `1.4`, `1.5`, `1.6`; нет ключа — 1. */
    const val ZOOM = "call.zoom"

    /** Рука: `right`, `left`, `both`; нет ключа — правая. */
    const val HAND = "call.hand"

    /** Ступени увеличения (заказчик 2026-11-10). */
    val ZOOMS = listOf(1f, 1.3f, 1.4f, 1.5f, 1.6f)

    /**
     * Сдвиг для ступени, проценты кадра (заказчик 2026-11-10): вниз всегда, вбок — от руки. Не дальше края кадра
     * («чтобы не выходило ничего»): при 1.3 до края 11,5 %, и 12 стало 11; при 1.4, 1.5, 1.6 до края 14,3, 16,7 и
     * 18,75 % — 14, 15 и 18 внутри.
     */
    private val SHIFTS = mapOf(1.3f to 12, 1.4f to 14, 1.5f to 15, 1.6f to 18)

    fun fresh(all: Map<String, String>): Boolean = all[LOOK] != "old"
    fun adapt(all: Map<String, String>): Boolean = all[ADAPT] == "1"

    fun zoom(all: Map<String, String>): Float = all[ZOOM]?.toFloatOrNull()?.takeIf { it in ZOOMS } ?: 1f

    fun hand(all: Map<String, String>): CallHand = when (all[HAND]) {
        "left" -> CallHand.Left
        "both" -> CallHand.Both
        else -> CallHand.Right
    }

    fun code(hand: CallHand): String = when (hand) {
        CallHand.Right -> "right"
        CallHand.Left -> "left"
        CallHand.Both -> "both"
    }

    /** Сдвиг ступени в процентах кадра — уже с пределом края. */
    fun shift(zoom: Float): Int {
        val table = SHIFTS[zoom] ?: return 0
        val edge = kotlin.math.floor((1f - 1f / zoom) / 2f * 100f).toInt()
        return minOf(table, edge)
    }

    /**
     * Обрезка с начала звонка: увеличение, вниз на сдвиг (на экране «−»), правая рука — влево («−»), левая — вправо,
     * обе — по середине.
     */
    fun preset(zoom: Float, hand: CallHand): CameraCrop {
        if (zoom <= 1f) return CameraCrop.FULL
        val k = shift(zoom) / 100f
        val x = when (hand) {
            CallHand.Right -> -k
            CallHand.Left -> k
            CallHand.Both -> 0f
        }
        return CameraCrop(zoom, x, k).clamped()
    }

    fun preset(all: Map<String, String>): CameraCrop = preset(zoom(all), hand(all))
}
