package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Кратность 16: какой способ выбран и что обрезается (заказчик 2026-09-29). */
class AlignmentTest {

    @Test
    fun без_кратности_галочки_не_действуют() {
        assertNull(VideoPreset(alignCrop = true, alignSingle = true).alignment)
    }

    @Test
    fun способ_по_галочкам() {
        assertEquals(Alignment.RequestAllLayers, VideoPreset(align16 = true).alignment)
        assertEquals(Alignment.RequestOneLayer, VideoPreset(align16 = true, alignSingle = true).alignment)
        assertEquals(Alignment.Crop, VideoPreset(align16 = true, alignCrop = true).alignment)
        assertEquals(Alignment.Crop, VideoPreset(align16 = true, alignCrop = true, alignSingle = true).alignment, "обрезка берёт верх")
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
