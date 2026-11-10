package io.tima.core.call

import kotlin.math.hypot

/**
 * Зоны морщин (ФЗ6, решение 4): пять областей по точкам лица — лоб, между бровями, под глазами, уголки глаз,
 * носогубные складки. Эллипс на зону: середина (текстурные координаты), полуоси (доли высоты кадра), ось (вдоль
 * лица), сила (ползунок). Лба в точках нет — он достраивается вверх от бровей на долю расстояния между глазами.
 *
 * [eyes] — сами глаза (два эллипса: середина, полуоси) с осью [eyeAxis] вдоль линии глаз: там зоны не действуют, куда
 * бы ни съехали при отставании точек (заказчик 2026-11-10: «что бы при перемещении не надвигались на глаза»).
 */
internal class WrinkleZones(
    val zones: FloatArray,
    val axes: FloatArray,
    val strengths: FloatArray,
    val eyes: FloatArray = FloatArray(8),
    val eyeAxis: FloatArray = floatArrayOf(1f, 0f),
) {
    companion object {
        val NONE = WrinkleZones(FloatArray(32), FloatArray(16), FloatArray(8))

        /** Уголки глаз: середина зоны — наружу от угла на эту долю расстояния между глазами (было 0,16 — заходила на глаз). */
        const val CORNER_OUT = 0.26f

        /** Полуоси зоны уголков: вдоль линии глаз и поперёк — морщины расходятся веером вверх и вниз. */
        const val CORNER_ALONG = 0.13f
        const val CORNER_ACROSS = 0.2f

        fun of(p: FloatArray, w: Int, h: Int, f: CallFilters): WrinkleZones {
            val aspect = w.toFloat() / h
            // Точки — в «единицах высоты»: x × (w / h), y как есть; так эллипсы круглые при любом кадре.
            fun x(i: Int) = p[i * 2] * aspect
            fun y(i: Int) = p[i * 2 + 1]
            val leftEye = x(74) to y(74)
            val rightEye = x(77) to y(77)
            val eyeDx = rightEye.first - leftEye.first
            val eyeDy = rightEye.second - leftEye.second
            val eyeDist = hypot(eyeDx, eyeDy).coerceAtLeast(1e-4f)
            val ux = eyeDx / eyeDist
            val uy = eyeDy / eyeDist
            // «Вверх по лицу» — от рта к глазам.
            val mouthX = (x(84) + x(90)) / 2
            val mouthY = (y(84) + y(90)) / 2
            var upX = -uy
            var upY = ux
            if (upX * ((leftEye.first + rightEye.first) / 2 - mouthX) + upY * ((leftEye.second + rightEye.second) / 2 - mouthY) < 0) {
                upX = -upX
                upY = -upY
            }
            val zones = FloatArray(32)
            val axes = FloatArray(16)
            val strengths = FloatArray(8)
            var n = 0
            fun zone(cx: Float, cy: Float, rx: Float, ry: Float, ax: Float, ay: Float, k: Int) {
                zones[n * 4] = cx / aspect
                zones[n * 4 + 1] = cy
                zones[n * 4 + 2] = rx
                zones[n * 4 + 3] = ry
                axes[n * 2] = ax
                axes[n * 2 + 1] = ay
                strengths[n] = k / 100f * CallFilters.WRINKLE_MAX
                n++
            }
            val browsX = (x(35) + x(40)) / 2
            val browsY = (y(35) + y(40)) / 2
            zone(browsX + upX * 0.55f * eyeDist, browsY + upY * 0.55f * eyeDist, 0.95f * eyeDist, 0.36f * eyeDist, ux, uy, f.forehead)
            zone((x(37) + x(38)) / 2, (y(37) + y(38)) / 2, 0.22f * eyeDist, 0.3f * eyeDist, ux, uy, f.brows)
            for (bottom in intArrayOf(73, 76)) {
                zone(x(bottom) - upX * 0.17f * eyeDist, y(bottom) - upY * 0.17f * eyeDist, 0.32f * eyeDist, 0.15f * eyeDist, ux, uy, f.underEyes)
            }
            for ((corner, side) in listOf(52 to -1f, 61 to 1f)) {
                zone(
                    x(corner) + side * ux * CORNER_OUT * eyeDist, y(corner) + side * uy * CORNER_OUT * eyeDist,
                    CORNER_ALONG * eyeDist, CORNER_ACROSS * eyeDist, ux, uy, f.eyeCorners,
                )
            }
            for ((wing, corner) in listOf(82 to 84, 83 to 90)) {
                val ax = x(corner) - x(wing)
                val ay = y(corner) - y(wing)
                val len = hypot(ax, ay).coerceAtLeast(1e-4f)
                zone((x(wing) + x(corner)) / 2, (y(wing) + y(corner)) / 2, len * 0.6f, 0.08f * eyeDist, ax / len, ay / len, f.nasolabial)
            }
            // Глаза: от угла до угла с запасом, высота — по верху и низу глаза (не меньше половины ширины).
            val eyes = FloatArray(8)
            for ((k, e) in listOf(intArrayOf(52, 55, 72, 73), intArrayOf(58, 61, 75, 76)).withIndex()) {
                val (outer, inner, top, bottom) = e.toList()
                val half = hypot(x(inner) - x(outer), y(inner) - y(outer)) / 2
                val tall = hypot(x(top) - x(bottom), y(top) - y(bottom)) / 2
                eyes[k * 4] = (x(outer) + x(inner)) / 2 / aspect
                eyes[k * 4 + 1] = (y(outer) + y(inner)) / 2
                eyes[k * 4 + 2] = half * 1.2f
                eyes[k * 4 + 3] = maxOf(tall * 1.6f, half * 0.55f)
            }
            return WrinkleZones(zones, axes, strengths, eyes, floatArrayOf(ux, uy))
        }
    }
}
