package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Обычный звонок: H.264 без запасного, потолок — от сервера (ПЛАН-ВИДЕО.md В3, В5б). */
class BasePresetTest {

    @Test
    fun потолок_от_сервера_становится_набором() {
        val preset = basePreset(VideoCeiling(width = 640, height = 480, fps = 15, bitrate = 500_000))
        assertEquals(640, preset.video.width)
        assertEquals(480, preset.video.height)
        assertEquals(15, preset.video.fps)
        assertEquals(500_000, preset.video.bitrate)
        assertEquals(500_000, preset.video.maxBitrate)
    }

    @Test
    fun сервер_не_прислал_потолок_берём_решение_заказчика() {
        val preset = basePreset(null)
        assertEquals(1280, preset.video.width)
        assertEquals(720, preset.video.height)
        assertEquals(24, preset.video.fps)
        assertEquals(800_000, preset.video.bitrate)
    }

    @Test
    fun кодек_h264_без_запасного_и_не_прогон() {
        val preset = basePreset(null)
        assertEquals(VideoCodec.H264, preset.video.codec)
        assertNull(preset.video.backup, "VP8 — элемент выбора, а не запасной")
        assertFalse(preset.exact)
        assertEquals(BASE_PRESET, preset.name)
    }
}
