package io.tima.core.call

import kotlin.math.hypot

/**
 * Точки лица в раскладке шейдеров (ПЛАН-(ФЗ) ФЗ4): 106 точек VNN и 5 досчитанных, как в gpupixel. Раскладка по
 * шаблону лица (`FACE_TEMPLATE`), слева направо — как в кадре, не как у человека:
 * 0–32 контур (левое ухо → подбородок 16 → правое), 33–42 верх бровей, 43–46 спинка носа, 47–51 низ носа,
 * 52–57 левый глаз (52 — внешний угол, 55 — внутренний), 58–63 правый (58 — внутренний, 61 — внешний),
 * 64–71 низ бровей, 72–77 верх, низ и середина глаз, 78–83 крылья носа, 84–95 губы снаружи (84 и 90 — уголки),
 * 96–103 губы внутри, 104–105 зрачки, 106–110 досчитанные.
 */
internal object FacePoints {
    /** Точек лица у шейдеров: 106 и 5 досчитанных (как в gpupixel). */
    const val FACE_POINTS = 111

    /**
     * MediaPipe Face Landmarker (478 точек с радужкой) → раскладка 106. Сопоставлено по смыслу точки: у MediaPipe
     * «правый глаз» — правый у человека, то есть **левый в кадре** камеры (кадр не зеркальный). Контур — отдельно,
     * пересэмплированием дуги [MP_JAW] в 33 точки.
     */
    private val MP_MAP: Map<Int, Int> = buildMap {
        // брови: верх (снаружи внутрь слева, изнутри наружу справа), низ — так же
        listOf(70, 63, 105, 66, 107).forEachIndexed { i, mp -> put(33 + i, mp) }
        listOf(336, 296, 334, 293, 300).forEachIndexed { i, mp -> put(38 + i, mp) }
        listOf(53, 52, 65, 55).forEachIndexed { i, mp -> put(64 + i, mp) }
        listOf(285, 295, 282, 283).forEachIndexed { i, mp -> put(68 + i, mp) }
        // нос: спинка сверху вниз, низ слева направо
        listOf(168, 6, 197, 195).forEachIndexed { i, mp -> put(43 + i, mp) }
        listOf(98, 97, 2, 326, 327).forEachIndexed { i, mp -> put(47 + i, mp) }
        // глаза
        listOf(33, 160, 158, 133, 153, 144).forEachIndexed { i, mp -> put(52 + i, mp) }
        listOf(362, 385, 387, 263, 373, 380).forEachIndexed { i, mp -> put(58 + i, mp) }
        put(72, 159); put(73, 145); put(74, 468)
        put(75, 386); put(76, 374); put(77, 473)
        // крылья носа
        put(78, 122); put(79, 351); put(80, 49); put(81, 279); put(82, 64); put(83, 294)
        // губы снаружи: левый уголок, верх слева направо, правый уголок, низ справа налево
        listOf(61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91).forEachIndexed { i, mp -> put(84 + i, mp) }
        // губы внутри
        listOf(78, 81, 13, 311, 308, 402, 14, 178).forEachIndexed { i, mp -> put(96 + i, mp) }
        put(104, 468); put(105, 473)
    }

    /** Нижняя дуга лица MediaPipe: от уха слева в кадре через подбородок (152) к уху справа. */
    private val MP_JAW = intArrayOf(234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454)

    /** 478 точек MediaPipe (x, y — доли кадра, y вниз) → 111 в той же системе координат. */
    fun fromMediaPipe(xs: FloatArray, ys: FloatArray): FloatArray {
        val out = FloatArray(FACE_POINTS * 2)
        jaw(xs, ys).forEachIndexed { i, (x, y) ->
            out[i * 2] = x
            out[i * 2 + 1] = y
        }
        for ((i, mp) in MP_MAP) {
            out[i * 2] = xs[mp]
            out[i * 2 + 1] = ys[mp]
        }
        extras(out)
        return out
    }

    /** 106 точек VNN (доли кадра, y вниз) → 111. */
    fun fromVnn(points106: FloatArray): FloatArray {
        val out = FloatArray(FACE_POINTS * 2)
        points106.copyInto(out, 0, 0, 212)
        extras(out)
        return out
    }

    /** Пять досчитанных точек — как gpupixel `FaceDetector::Detect`: середины пар. */
    private fun extras(p: FloatArray) {
        fun mid(at: Int, a: Int, b: Int) {
            p[at * 2] = (p[a * 2] + p[b * 2]) / 2
            p[at * 2 + 1] = (p[a * 2 + 1] + p[b * 2 + 1]) / 2
        }
        mid(106, 102, 98)
        mid(107, 35, 65)
        mid(108, 70, 40)
        mid(109, 5, 80)
        mid(110, 81, 27)
    }

    /** Дуга [MP_JAW] → 33 точки поровну по длине. */
    private fun jaw(xs: FloatArray, ys: FloatArray): List<Pair<Float, Float>> {
        val px = MP_JAW.map { xs[it] }
        val py = MP_JAW.map { ys[it] }
        val seg = FloatArray(MP_JAW.size)
        for (i in 1 until MP_JAW.size) seg[i] = seg[i - 1] + hypot(px[i] - px[i - 1], py[i] - py[i - 1])
        val total = seg.last().coerceAtLeast(1e-6f)
        return (0 until 33).map { k ->
            val at = total * k / 32f
            var i = 1
            while (i < seg.lastIndex && seg[i] < at) i++
            val span = (seg[i] - seg[i - 1]).coerceAtLeast(1e-6f)
            val t = ((at - seg[i - 1]) / span).coerceIn(0f, 1f)
            (px[i - 1] + (px[i] - px[i - 1]) * t) to (py[i - 1] + (py[i] - py[i - 1]) * t)
        }
    }

    /**
     * Прямой кадр (u — вправо, v — вниз, как видит человек) → буфер ещё не повёрнутый (bx, by; верх — ноль).
     * Буфер показывается повёрнутым на [rotation] по часовой: при 90 точка буфера (bx, by) встаёт в (1 − by, bx).
     */
    fun uprightToBuffer(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (((rotation % 360) + 360) % 360) {
        90 -> v to 1f - u
        180 -> 1f - u to 1f - v
        270 -> 1f - v to u
        else -> u to v
    }
}
