package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals

/** Выбор кодера прогона сильнее «Настройки → Звонки» (заказчик 2026-09-30, 1а). */
class CoderChoiceTest {

    private val всёАппаратно = HardwareCoding(encode = true, decode = true)
    private val всёПрограммно = HardwareCoding(encode = false, decode = false)

    @Test
    fun обычный_звонок_идёт_по_настройкам() {
        assertEquals(всёПрограммно, всёПрограммно.forRun(null))
    }

    @Test
    fun как_в_настройках_следует_за_переключателями() {
        val набор = VideoPreset()
        assertEquals(всёАппаратно, всёАппаратно.forRun(набор))
        assertEquals(всёПрограммно, всёПрограммно.forRun(набор))
    }

    @Test
    fun выбор_прогона_сильнее_настроек_и_каждый_за_себя() {
        val набор = VideoPreset(encoder = CoderChoice.Software, decoder = CoderChoice.Hardware)
        assertEquals(HardwareCoding(encode = false, decode = true), всёАппаратно.forRun(набор))
        assertEquals(HardwareCoding(encode = false, decode = true), всёПрограммно.forRun(набор))
    }

    @Test
    fun файл_наборов_хранит_выбор() {
        val был = PublishPreset(name = "vp8 прог", video = VideoPreset(codec = VideoCodec.VP8, maxBitrate = 800_000, encoder = CoderChoice.Software, decoder = CoderChoice.Hardware))
        val стал = presetsFromJson(presetsToJson(listOf(был))).single()
        assertEquals(CoderChoice.Software, стал.video.encoder)
        assertEquals(CoderChoice.Hardware, стал.video.decoder)
    }
}
