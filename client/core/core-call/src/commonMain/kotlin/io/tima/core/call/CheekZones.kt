package io.tima.core.call

import kotlin.math.hypot

/**
 * Румяна новых стилей (заказчик 2026-11-12: «Сделать яркий центрированный, аниме румянец, разные цвета»): на каждую щёку
 * — овал с картинкой стиля, центр — яблоко щеки, оси — вдоль линии глаз и «вверх по лицу», как у зон морщин.
 *
 * Координаты для шейдера `FS_CHEEK`: [centers] — текстурные (как точки), [axes] и [ups] — единичные векторы в «единицах
 * высоты» (x × w/h), [halves] — полуоси в долях высоты кадра. Ось [axes] смотрит **наружу** от носа: у левой щеки
 * влево, у правой вправо — картинка зеркалится, блик и штрихи стоят у обеих щёк одинаково снаружи.
 */
internal class CheekZones(val centers: FloatArray, val axes: FloatArray, val ups: FloatArray, val halves: FloatArray) {
    companion object {
        /** Середина щеки: от зрачка вниз по лицу на столько расстояний между глазами… */
        const val DOWN = 0.62f

        /** …и наружу. */
        const val OUT = 0.12f

        /** Полуоси овала в расстояниях между глазами: вдоль и поперёк линии глаз. */
        val HALF = mapOf(
            BlushStyle.Bright to (0.46f to 0.40f),
            BlushStyle.Anime to (0.50f to 0.42f),
            BlushStyle.AnimeStrokes to (0.52f to 0.36f),
        )

        fun of(p: FloatArray, w: Int, h: Int, style: BlushStyle): CheekZones {
            val aspect = w.toFloat() / h
            fun x(i: Int) = p[i * 2] * aspect
            fun y(i: Int) = p[i * 2 + 1]
            val ex = x(77) - x(74)
            val ey = y(77) - y(74)
            val eyeDist = hypot(ex, ey).coerceAtLeast(1e-4f)
            val ux = ex / eyeDist
            val uy = ey / eyeDist
            // «Вверх по лицу» — от рта к глазам, как у морщин.
            var upX = -uy
            var upY = ux
            val mx = (x(84) + x(90)) / 2 - (x(74) + x(77)) / 2
            val my = (y(84) + y(90)) / 2 - (y(74) + y(77)) / 2
            if (upX * mx + upY * my > 0) {
                upX = -upX
                upY = -upY
            }
            // Поворот головы: дальняя щека уже — по видимой ширине от носа до края лица (точки 4 и 28 контура).
            val nose = x(46) to y(46)
            fun across(i: Int) = kotlin.math.abs((x(i) - nose.first) * ux + (y(i) - nose.second) * uy)
            val left = across(4)
            val right = across(28)
            val sum = (left + right).coerceAtLeast(1e-4f)
            val (halfAlong, halfUp) = HALF[style] ?: (0.36f to 0.32f)
            val centers = FloatArray(4)
            val axes = FloatArray(4)
            val ups = FloatArray(4)
            val halves = FloatArray(4)
            for ((k, eye, side, part) in listOf(Quad(0, 74, -1f, left), Quad(1, 77, 1f, right))) {
                val turn = (part / sum * 2f).coerceIn(0.45f, 1.25f)
                val cx = x(eye) - upX * DOWN * eyeDist + side * ux * OUT * eyeDist
                val cy = y(eye) - upY * DOWN * eyeDist + side * uy * OUT * eyeDist
                centers[k * 2] = cx / aspect
                centers[k * 2 + 1] = cy
                axes[k * 2] = side * ux
                axes[k * 2 + 1] = side * uy
                ups[k * 2] = upX
                ups[k * 2 + 1] = upY
                halves[k * 2] = halfAlong * eyeDist * turn
                halves[k * 2 + 1] = halfUp * eyeDist
            }
            return CheekZones(centers, axes, ups, halves)
        }

        private data class Quad(val k: Int, val eye: Int, val side: Float, val part: Float)
    }
}
