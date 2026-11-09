package io.tima.core.call

import kotlin.math.abs

/**
 * Режим адаптации изображения (ПЛАН-(ЗН) ЗН5, заказчик 2026-11-10): **своё** изображение
 * приближается обрезкой кадра камеры перед отправкой — собеседник видит то же, что своё окошко.
 *
 * [zoom] — ступень из [STEPS]; [x], [y] — середина видимой части относительно середины кадра,
 * долями ширины и высоты кадра, как его видит человек (уже повёрнутого): вправо и вниз —
 * положительные. Сдвиг не выходит за край кадра ([clamped]).
 */
data class CameraCrop(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    val full: Boolean get() = zoom <= 1f

    /** Насколько середина может отойти от середины кадра: видна 1/zoom, остальное — поровну с краёв. */
    private val reach: Float get() = (1f - 1f / zoom) / 2f

    fun clamped(): CameraCrop = if (full) FULL else copy(x = x.coerceIn(-reach, reach), y = y.coerceIn(-reach, reach))

    /** «+»: следующая ступень, сдвиг сохраняется в пределах нового края. */
    fun closer(): CameraCrop = copy(zoom = STEPS.firstOrNull { it > zoom + EPS } ?: zoom).clamped()

    /** «−»: прежняя ступень; на 1× — весь кадр. */
    fun farther(): CameraCrop = copy(zoom = STEPS.lastOrNull { it < zoom - EPS } ?: 1f).clamped()

    /** Стрелки: шаг — четверть видимой части; [dx], [dy] — −1, 0 или 1. */
    fun moved(dx: Int, dy: Int): CameraCrop = copy(x = x + dx * step, y = y + dy * step).clamped()

    private val step: Float get() = 1f / zoom / 4f

    val canCloser: Boolean get() = STEPS.any { it > zoom + EPS }
    val canFarther: Boolean get() = !full
    fun canMove(dx: Int, dy: Int): Boolean =
        !full && ((dx < 0 && x > -reach + EPS) || (dx > 0 && x < reach - EPS) || (dy < 0 && y > -reach + EPS) || (dy > 0 && y < reach - EPS))

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
        // Сдвиг экрана → сдвиг буфера: поворот на 90 по часовой кладёт ось y буфера влево.
        val (bx, by) = when (((rotation % 360) + 360) % 360) {
            90 -> c.y to -c.x
            180 -> -c.x to -c.y
            270 -> -c.y to c.x
            else -> c.x to c.y
        }
        val left = ((width - w) / 2f + bx * width).toInt().coerceIn(0, width - w) and 1.inv()
        val top = ((height - h) / 2f + by * height).toInt().coerceIn(0, height - h) and 1.inv()
        return Region(left, top, w, h)
    }

    data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    companion object {
        val FULL = CameraCrop()

        /** Ступени приближения (ответ заказчика 2: до 3×). */
        val STEPS = listOf(1f, 1.5f, 2f, 3f)

        private const val EPS = 0.001f

        fun label(zoom: Float): String = if (zoom == zoom.toInt().toFloat()) zoom.toInt().toString() else zoom.toString()

        private fun round2(v: Float): String = ((v * 100).toInt() / 100f).toString()
    }
}

/**
 * «Настройки → Звонок · Вид» (ПЛАН-(ЗН) ЗН1). В настройках устройства: у другого телефона свой
 * экран и свой выбор.
 */
object CallLookKeys {
    /** `old` — прежний вид окна звонка; нет ключа или иное — новый (ответ заказчика 5: по умолчанию новый). */
    const val LOOK = "call.look"

    /** `1` — режим адаптации изображения включён; по умолчанию выключен. */
    const val ADAPT = "call.adapt"

    fun fresh(all: Map<String, String>): Boolean = all[LOOK] != "old"
    fun adapt(all: Map<String, String>): Boolean = all[ADAPT] == "1"
}
