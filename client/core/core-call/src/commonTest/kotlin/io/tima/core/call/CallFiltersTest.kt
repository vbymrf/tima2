package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Фильтры звонка (ПЛАН-(ФЗ)): настройки, точки лица, зоны морщин. */
class CallFiltersTest {

    @Test
    fun настройки_помнятся_и_читаются_обратно() {
        val f = CallFilters(on = true, smooth = 40, whiten = 20, brightness = -15, look = ColorLook.Light, lookStrength = 60, thin = 30, blush = 50, underEyes = 70, source = FaceSource.Vnn)
        assertEquals(f, CallFilterKeys.read(CallFilterKeys.write(f)))
        assertEquals(CallFilters.OFF.copy(source = FaceSource.Vnn), CallFilterKeys.read(emptyMap()))
        // Мусор в настройках — в пределы, а не падение.
        assertEquals(100, CallFilterKeys.read(mapOf(CallFilterKeys.PREFIX + "smooth" to "500")).smooth)
    }

    @Test
    fun тоньше_до_012_и_цвета_помнятся() {
        // Заказчик 2026-11-10: предел 0,12; первая половина хода — как у gpupixel (0,025 на 50).
        assertEquals(0f, CallFilters(thin = 0).thinDelta)
        assertEquals(0.025f, CallFilters(thin = 50).thinDelta, 1e-6f)
        assertEquals(0.12f, CallFilters(thin = 100).thinDelta, 1e-6f)
        val f = CallFilters(on = true, lipstick = 40, lipColor = LipColor.Cherry, blush = 30, blushColor = BlushColor.Peach)
        assertEquals(f, CallFilterKeys.read(CallFilterKeys.write(f)))
    }

    @Test
    fun выключено_значит_кадр_как_снят() {
        val f = CallFilters(on = false, smooth = 80, thin = 50)
        assertFalse(f.active)
        assertFalse(f.needsFace)
        assertEquals("выкл", f.describe())
        assertTrue(f.copy(on = true).active)
        assertTrue(f.copy(on = true).needsFace, "тоньше лицо — по точкам лица")
        assertFalse(CallFilters(on = true, smooth = 50).needsFace, "сглаживание без зон — точек не нужно")
        assertTrue(CallFilters(on = true, forehead = 10).needsFace, "морщины по областям — по точкам")
    }

    @Test
    fun поворот_прямого_кадра_и_обратно() {
        // Прямой → буфер и обратно (буфер показывается повёрнутым по часовой): точка встаёт на своё место.
        for (r in listOf(0, 90, 180, 270)) {
            val (bx, by) = FacePoints.uprightToBuffer(0.2f, 0.7f, r)
            val (u, v) = when (r) {
                90 -> 1f - by to bx
                180 -> 1f - bx to 1f - by
                270 -> by to 1f - bx
                else -> bx to by
            }
            assertEquals(0.2f, u, 1e-5f, "поворот $r")
            assertEquals(0.7f, v, 1e-5f, "поворот $r")
        }
    }

    @Test
    fun из_vnn_досчитываются_пять_точек() {
        val raw = FloatArray(212) { it / 212f }
        val p = FacePoints.fromVnn(raw)
        assertEquals(222, p.size)
        // 106 — середина 102 и 98, как в gpupixel.
        assertEquals((raw[204] + raw[196]) / 2, p[212], 1e-6f)
    }

    @Test
    fun из_mediapipe_контур_через_подбородок() {
        // Лицо-образец: дуга нижней челюсти — полуокружность, остальные точки — по середине.
        val xs = FloatArray(478) { 0.5f }
        val ys = FloatArray(478) { 0.5f }
        val jaw = intArrayOf(234, 93, 132, 58, 172, 136, 150, 149, 176, 148, 152, 377, 400, 378, 379, 365, 397, 288, 361, 323, 454)
        jaw.forEachIndexed { i, mp ->
            val a = kotlin.math.PI * i / (jaw.size - 1)
            xs[mp] = 0.5f - 0.3f * kotlin.math.cos(a).toFloat()
            ys[mp] = 0.4f + 0.3f * kotlin.math.sin(a).toFloat()
        }
        val p = FacePoints.fromMediaPipe(xs, ys)
        assertEquals(0.2f, p[0], 1e-3f, "точка 0 — ухо слева в кадре")
        assertEquals(0.5f, p[16 * 2], 2e-3f, "точка 16 — подбородок посередине")
        assertEquals(0.7f, p[16 * 2 + 1], 2e-3f)
        assertEquals(0.8f, p[32 * 2], 1e-3f, "точка 32 — ухо справа")
    }

    @Test
    fun лоб_над_бровями_а_под_глазами_ниже_глаз() {
        // Лицо прямо, в текстурных координатах (низ — ноль): глаза на 0,6, брови выше, рот ниже.
        val p = FloatArray(222)
        fun put(i: Int, x: Float, y: Float) { p[i * 2] = x; p[i * 2 + 1] = y }
        put(74, 0.4f, 0.6f); put(77, 0.6f, 0.6f)
        put(84, 0.45f, 0.4f); put(90, 0.55f, 0.4f)
        put(35, 0.39f, 0.66f); put(40, 0.61f, 0.66f); put(37, 0.46f, 0.65f); put(38, 0.54f, 0.65f)
        put(73, 0.4f, 0.58f); put(76, 0.6f, 0.58f); put(52, 0.36f, 0.6f); put(61, 0.64f, 0.6f)
        put(82, 0.46f, 0.47f); put(83, 0.54f, 0.47f)
        val z = WrinkleZones.of(p, 720, 720, CallFilters(on = true, forehead = 100, underEyes = 50))
        assertTrue(z.zones[1] > 0.66f, "лоб не выше бровей: ${z.zones[1]}")
        assertTrue(z.zones[2 * 4 + 1] < 0.58f, "«под глазами» не ниже глаз: ${z.zones[2 * 4 + 1]}")
        // Предел морщин поднят (заказчик 2026-11-10): ползунок 100 — сила 1,6.
        assertEquals(CallFilters.WRINKLE_MAX, z.strengths[0], 1e-6f)
        assertEquals(0.5f * CallFilters.WRINKLE_MAX, z.strengths[2], 1e-6f)
        assertEquals(0f, z.strengths[1], "между бровями выключено")
        assertNotNull(z.axes)
    }
}
