package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Обрезка до кратного 16: что обрезается и что кратно (ПЛАН-ВИДЕО.md В2, заказчик 2026-09-30). */
class AlignmentTest {

    @Test
    fun обрезка_по_умолчанию_включена() {
        // Решение заказчика 2026-09-30: в каждом звонке, а галочка — только выключить.
        assertFalse(VideoPreset().noCrop)
    }

    @Test
    fun кратен_ли_кадр() {
        assertTrue(CenterCrop.aligned(256, 480))
        assertTrue(CenterCrop.aligned(720, 1280))
        assertFalse(CenterCrop.aligned(270, 480), "ширина 270 — не кратна")
        assertFalse(CenterCrop.aligned(540, 960))
        assertFalse(CenterCrop.aligned(352, 469), "нечётная сторона от заявки кратности")
        assertTrue(CenterCrop.aligned(8, 480), "сторону меньше 16 не режут — считается кратной")
    }

    @Test
    fun _270_на_480_обрезается_по_центру_до_256_на_480() {
        // Размер, на котором полосил realme 2026-09-29.
        val crop = CenterCrop(270, 480)

        assertEquals(256, crop.alignedWidth)
        assertEquals(480, crop.alignedHeight)
        assertEquals(listOf(6, 0, 256, 480), crop.region(270, 480), "6 точек слева, 8 справа: смещение чётное")
    }

    @Test
    fun _360_на_640_теряет_по_4_точки_слева_и_справа() {
        val crop = CenterCrop(360, 640)

        assertEquals(listOf(4, 0, 352, 640), crop.region(360, 640))
    }

    @Test
    fun кратный_размер_не_обрезается() {
        assertTrue(CenterCrop(640, 480).none)
        assertTrue(CenterCrop(1280, 720).none)
    }

    @Test
    fun кадр_другого_размера_режется_той_же_долей() {
        // Кодер заведён под 270×480, а пришёл кадр вдвое больше — вырезается та же середина.
        assertEquals(listOf(14, 0, 512, 960), CenterCrop(270, 480).region(540, 960))
    }

    @Test
    fun сторона_меньше_16_не_трогается() {
        assertEquals(8, CenterCrop(8, 480).alignedWidth)
    }
}
