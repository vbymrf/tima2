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

    /**
     * Сглаживание точек по скорости (заказчик 2026-11-10: на большой силе помада «не поспевает за движением губ»).
     * Было одно на всё: новое положение брали на 0,6 — губы при разговоре догоняла маска за три поиска, ~450 мс.
     * Теперь у каждой точки своё: сдвиг меньше дрожи VNN — берётся на [FOLLOW_STILL] (лицо стоит — не дрожит),
     * сдвиг от [FOLLOW_FULL] доли кадра и больше — целиком (губы движутся — без отставания), между — по прямой.
     */
    fun follow(was: FloatArray, raw: FloatArray): FloatArray {
        val out = FloatArray(raw.size)
        for (i in 0 until raw.size / 2) {
            val dx = raw[i * 2] - was[i * 2]
            val dy = raw[i * 2 + 1] - was[i * 2 + 1]
            val moved = kotlin.math.sqrt(dx * dx + dy * dy)
            val k = FOLLOW_STILL + (1f - FOLLOW_STILL) * (moved / FOLLOW_FULL).coerceIn(0f, 1f)
            out[i * 2] = was[i * 2] + dx * k
            out[i * 2 + 1] = was[i * 2 + 1] + dy * k
        }
        return out
    }

    /** Доля нового положения у стоящей точки. */
    const val FOLLOW_STILL = 0.4f

    /** Сдвиг (доля кадра поиска, ~2 пикселя из 192), от которого точка берётся целиком. */
    const val FOLLOW_FULL = 0.012f

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
