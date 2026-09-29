package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Кто что примет — и что из этого следует для отправителя (ПЛАН-ВИДЕО.md В5). */
class PeerCodecsTest {

    private val all = setOf(VideoCodec.H264, VideoCodec.VP8, VideoCodec.VP9)

    @Test
    fun атрибут_туда_и_обратно() {
        val written = PeerCodecs.write(setOf(VideoCodec.VP8, VideoCodec.H264))
        assertEquals(setOf(VideoCodec.VP8, VideoCodec.H264), PeerCodecs.read(written))
        assertNull(PeerCodecs.read(null), "нет атрибута — не знаем, а не «ничего»")
        assertNull(PeerCodecs.read(""))
        assertEquals(setOf(VideoCodec.VP8), PeerCodecs.read("vp8, av1"), "чужой кодек отбрасывается")
    }

    @Test
    fun оба_принимают_h264_значит_h264() {
        assertEquals(VideoCodec.H264, PeerCodecs.choose(VideoCodec.H264, all, listOf(all)))
    }

    @Test
    fun собеседник_без_h264_получает_vp8() {
        // Раскодирование выключено, системного H.264 нет — примет только VP8/VP9.
        assertEquals(VideoCodec.VP8, PeerCodecs.choose(VideoCodec.H264, all, listOf(setOf(VideoCodec.VP8, VideoCodec.VP9))))
    }

    @Test
    fun не_знаем_значит_vp8() {
        assertEquals(VideoCodec.VP8, PeerCodecs.choose(VideoCodec.H264, all, listOf(null)))
    }

    @Test
    fun никого_нет_решаем_по_себе() {
        assertEquals(VideoCodec.H264, PeerCodecs.choose(VideoCodec.H264, all, emptyList()))
        assertEquals(VideoCodec.VP8, PeerCodecs.choose(VideoCodec.H264, setOf(VideoCodec.VP8, VideoCodec.VP9), emptyList()))
    }

    @Test
    fun vp9_в_выбор_не_попадает_даже_если_все_умеют() {
        assertEquals(VideoCodec.VP8, PeerCodecs.choose(VideoCodec.H264, setOf(VideoCodec.VP8, VideoCodec.VP9), listOf(all)))
    }

    @Test
    fun переключатели_по_умолчанию_включены() {
        assertEquals(HardwareCoding(), HardwareCodingKeys.read(emptyMap()))
        assertEquals(HardwareCoding(encode = false, decode = true), HardwareCodingKeys.read(mapOf(HardwareCodingKeys.ENCODE to "0")))
    }
}
