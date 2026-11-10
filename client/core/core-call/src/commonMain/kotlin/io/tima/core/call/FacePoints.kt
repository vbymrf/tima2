package io.tima.core.call

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
