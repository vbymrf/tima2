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
        val f = CallFilters(on = true, smooth = 40, whiten = 20, brightness = -15, look = ColorLook.Light, lookStrength = 60, thin = 30, blush = 50, underEyes = 70)
        assertEquals(f, CallFilterKeys.read(CallFilterKeys.write(f)))
        assertEquals(CallFilters.OFF, CallFilterKeys.read(emptyMap()))
        // Мусор в настройках — в пределы, а не падение.
        assertEquals(100, CallFilterKeys.read(mapOf(CallFilterKeys.PREFIX + "smooth" to "500")).smooth)
    }

    @Test
    fun тоньше_до_012() {
        // Заказчик 2026-11-10: предел 0,12, сила растёт ровно по ползунку.
        assertEquals(0f, CallFilters(thin = 0).thinDelta)
        assertEquals(0.06f, CallFilters(thin = 50).thinDelta, 1e-6f)
        assertEquals(0.12f, CallFilters(thin = 100).thinDelta, 1e-6f)
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
    fun точки_стоят_не_дрожат_а_движутся_без_отставания() {
        // Заказчик 2026-11-10: на большой силе помада «не поспевает за движением губ».
        val was = floatArrayOf(0.5f, 0.5f, 0.5f, 0.6f)
        val raw = floatArrayOf(0.502f, 0.5f, 0.55f, 0.6f)
        val p = FacePoints.follow(was, raw)
        // Дрожь в полпикселя — берётся меньше половины пути.
        assertTrue(p[0] - 0.5f < 0.002f * 0.6f, "дрожь не гасится: ${p[0]}")
        // Губы сдвинулись на 0,05 кадра — точка там же, где найдена.
        assertEquals(0.55f, p[2], 1e-6f)
        assertEquals(0.6f, p[3], 1e-6f)
    }

    @Test
    fun помада_гаснет_пока_рот_движется_и_возвращается() {
        // Заказчик 2026-11-10: «при значительном сдвиге быстро убирать маску», «с раскрытием рта, гасить частично».
        fun mouth(open: Float, shift: Float = 0f) = FloatArray(222).also { p ->
            fun put(i: Int, x: Float, y: Float) { p[i * 2] = x; p[i * 2 + 1] = y }
            put(84, 0.4f + shift, 0.7f); put(90, 0.6f + shift, 0.7f)
            put(87, 0.5f + shift, 0.7f - open); put(93, 0.5f + shift, 0.7f + open)
        }
        val closed = mouth(0.01f)
        assertEquals(0f, LipFade.motion(closed, closed, 100, 100), 1e-6f)
        // Рот открылся, уголки на месте: раскрытие выросло на 0,06 при ширине рта 0,2 — движение 0,3.
        assertEquals(0.3f, LipFade.motion(closed, mouth(0.04f), 100, 100), 1e-4f)
        // Голова сдвинулась на 0,05 — уголки на четверть ширины рта.
        assertEquals(0.25f, LipFade.motion(closed, mouth(0.01f, 0.05f), 100, 100), 1e-4f)

        val fade = LipFade()
        fade.onDetection(0f, 1000)
        assertEquals(1f, fade.value(1000))
        fade.onDetection(0.3f, 1100)
        assertEquals(LipFade.LOW, fade.value(1100 + LipFade.DOWN_MS.toLong()), 1e-5f, "гаснет быстро и не до нуля")
        fade.onDetection(0.01f, 1300)
        assertEquals(LipFade.LOW, fade.value(1400), 1e-5f, "один спокойный поиск — ещё не возврат")
        fade.onDetection(0.01f, 1450)
        assertEquals(1f, fade.value(1450 + LipFade.UP_MS.toLong()), 1e-5f, "два спокойных — плавно назад")
    }

    @Test
    fun уголки_глаз_снаружи_глаза_и_глаза_защищены() {
        // Заказчик 2026-11-10: «Уголки глаз — сдвиг на края… что бы при перемещении не надвигались на глаза».
        val p = FloatArray(222)
        fun put(i: Int, x: Float, y: Float) { p[i * 2] = x; p[i * 2 + 1] = y }
        put(74, 0.41f, 0.6f); put(77, 0.59f, 0.6f)
        put(52, 0.36f, 0.6f); put(55, 0.46f, 0.6f); put(72, 0.41f, 0.62f); put(73, 0.41f, 0.58f)
        put(58, 0.54f, 0.6f); put(61, 0.64f, 0.6f); put(75, 0.59f, 0.62f); put(76, 0.59f, 0.58f)
        put(84, 0.45f, 0.4f); put(90, 0.55f, 0.4f)
        val z = WrinkleZones.of(p, 720, 720, CallFilters(on = true, eyeCorners = 100))
        val eyeDist = 0.18f
        // Зона левого уголка (пятая): внутренний край — снаружи угла глаза.
        val innerEdge = z.zones[4 * 4] + WrinkleZones.CORNER_ALONG * eyeDist
        assertTrue(innerEdge < 0.36f, "зона заходит на глаз: край $innerEdge")
        assertTrue(z.zones[5 * 4] - WrinkleZones.CORNER_ALONG * eyeDist > 0.64f, "правая зона заходит на глаз")
        // Глаз: середина между углами, полуось не меньше половины ширины.
        assertEquals(0.41f, z.eyes[0], 1e-5f)
        assertTrue(z.eyes[2] >= 0.05f && z.eyes[3] > 0f)
        assertEquals(0.59f, z.eyes[4], 1e-5f)
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
