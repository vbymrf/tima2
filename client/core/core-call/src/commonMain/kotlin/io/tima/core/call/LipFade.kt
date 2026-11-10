package io.tima.core.call

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Помада гаснет, пока рот движется (заказчик 2026-11-10: «при значительном сдвиге быстро убирать маску и ставить её
 * на место потом», «с раскрытием рта, гасить частично»).
 *
 * Точки лица ищутся раз в несколько кадров, и между поисками маска рисуется по старым — на большой силе это видно
 * как цвет, который едет за губами. Движение меряется после каждого поиска по уголкам губ (поворот, улыбка) **и** по
 * раскрытию рта (при речи уголки почти стоят, а нижняя губа ходит), в долях ширины рта — порог один, близко лицо или
 * далеко. Гаснет не до нуля, а до [LOW]: при малой силе сдвиг не виден, а полное погасание при речи мигало бы.
 * Порог погасания выше порога возврата — на границе не дёргается.
 */
internal class LipFade {
    private var level = 1f
    private var target = 1f
    private var at = 0L
    private var calm = 0

    /** После поиска: [motion] — из [motion], [nowMs] — часы кадров. */
    fun onDetection(motion: Float, nowMs: Long) = synchronized(this) {
        advance(nowMs)
        when {
            motion > MOVING -> {
                target = LOW
                calm = 0
            }
            motion < CALM -> if (++calm >= CALM_DETECTIONS) target = 1f
            else -> calm = 0
        }
    }

    /** Доля силы помады на кадр: 1 — как на ползунке, [LOW] — рот движется. */
    fun value(nowMs: Long): Float = synchronized(this) {
        advance(nowMs)
        level
    }

    fun reset() = synchronized(this) {
        level = 1f
        target = 1f
        calm = 0
    }

    private fun advance(nowMs: Long) {
        val dt = if (at == 0L) 0L else (nowMs - at).coerceAtLeast(0L)
        at = nowMs
        if (level == target) return
        val span = 1f - LOW
        level = if (target < level) {
            max(target, level - span * dt / DOWN_MS)
        } else {
            minOf(target, level + span * dt / UP_MS)
        }
    }

    companion object {
        /** До скольких гаснет. */
        const val LOW = 0.3f

        /** Сдвиг уголка или раскрытия рта за один поиск, доля ширины рта: больше — гасить. */
        const val MOVING = 0.12f

        /** Меньше — спокойно (дрожь точек VNN ~2 % ширины рта). */
        const val CALM = 0.06f

        /** Сколько спокойных поисков подряд до возврата. */
        const val CALM_DETECTIONS = 2

        /** За сколько гаснет и за сколько возвращается, мс. */
        const val DOWN_MS = 60f
        const val UP_MS = 200f

        private const val LEFT = 84
        private const val RIGHT = 90
        private const val TOP = 87
        private const val BOTTOM = 93

        /**
         * Движение рта между двумя поисками: больший из сдвигов уголков и изменения раскрытия, в долях ширины рта.
         * Точки — доли кадра [width]×[height] (раскладка `FacePoints`), пересчитываются в пиксели: кадр не квадратный.
         */
        fun motion(was: FloatArray, now: FloatArray, width: Int, height: Int): Float {
            fun x(p: FloatArray, i: Int) = p[i * 2] * width
            fun y(p: FloatArray, i: Int) = p[i * 2 + 1] * height
            fun dist(p: FloatArray, a: Int, q: FloatArray, b: Int) = hypot(x(p, a) - x(q, b), y(p, a) - y(q, b))
            val mouth = dist(now, LEFT, now, RIGHT).coerceAtLeast(1f)
            val corners = max(dist(was, LEFT, now, LEFT), dist(was, RIGHT, now, RIGHT))
            val opening = abs(dist(now, TOP, now, BOTTOM) - dist(was, TOP, was, BOTTOM))
            return max(corners, opening) / mouth
        }
    }
}
