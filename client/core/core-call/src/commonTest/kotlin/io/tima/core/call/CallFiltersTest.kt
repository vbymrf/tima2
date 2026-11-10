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

/** Поиск лица моделями MediaPipe без MediaPipe Tasks (ПЛАН-(ФЗ) §6.3, вариант «в»). */
class FaceModelTest {

    @Test
    fun якорей_896_и_первый_в_углу() {
        assertEquals(896 * 2, FaceModel.anchors.size)
        assertEquals(0.5f / 16, FaceModel.anchors[0], 1e-6f)
        // Последний — правая нижняя клетка сетки 8×8.
        assertEquals(7.5f / 8, FaceModel.anchors[895 * 2], 1e-6f)
    }

    @Test
    fun лицо_у_якоря_со_сдвигом() {
        val boxes = FloatArray(896 * 16)
        val scores = FloatArray(896) { -10f }
        scores[100] = 5f
        boxes[100 * 16] = 6.4f // +0,05 холста по x
        boxes[100 * 16 + 2] = 64f // ширина — половина холста
        boxes[100 * 16 + 3] = 64f
        val d = assertNotNull(FaceModel.decode(boxes, scores))
        assertEquals(FaceModel.anchors[200] + 0.05f, d.cx, 1e-5f)
        assertEquals(0.5f, d.w, 1e-6f)
        assertEquals(null, FaceModel.decode(FloatArray(896 * 16), FloatArray(896) { -10f }), "все ниже порога — лица нет")
    }

    @Test
    fun кадр_вписан_в_холст_полями() {
        // Прямой кадр 192×256 → холст 128: масштаб 0,5, поля по бокам по 16.
        val box = FaceModel.letterbox(192, 256)
        assertEquals(0.5f, box.scale, 1e-6f)
        assertEquals(16f, box.offX, 1e-6f)
        val (x, y) = box.toFrame(0.5f, 0.5f)
        assertEquals(96f, x, 1e-4f)
        assertEquals(128f, y, 1e-4f)
    }

    @Test
    fun вырез_по_линии_глаз() {
        // Лицо наклонено: правый глаз (33) ниже левого (263) — ось выреза идёт вдоль линии глаз.
        val xs = FloatArray(478) { 100f }
        val ys = FloatArray(478) { 100f }
        xs[33] = 80f; ys[33] = 110f
        xs[263] = 120f; ys[263] = 90f
        xs[0] = 60f; xs[1] = 140f; ys[2] = 60f; ys[3] = 140f
        val roi = FaceModel.roiFrom(xs, ys)
        assertEquals(kotlin.math.atan2(-20f, 40f), roi.angle, 1e-5f)
        assertEquals(120f, roi.size, 1e-4f, "рамка 80 × 1,5")
        val (cx, cy) = roi.toFrame(0.5f, 0.5f)
        assertEquals(roi.cx, cx, 1e-4f)
        assertEquals(roi.cy, cy, 1e-4f)
        // Точка правее середины выреза уходит вдоль линии глаз — вверх-вправо.
        val (rx, ry) = roi.toFrame(1f, 0.5f)
        assertTrue(rx > cx && ry < cy)
    }
}
