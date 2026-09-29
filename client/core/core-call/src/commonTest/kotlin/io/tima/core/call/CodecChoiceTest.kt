package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecChoiceTest {

    /** Honor 8S: аппаратный H.264 WebRTC не берёт, H.265 нет, VP9 — программный. */
    private val honor = setOf(VideoCodec.VP9)

    @Test
    fun умеемый_кодек_пресета_остаётся() {
        val choice = CodecChoice.pick(VideoCodec.H264, VideoCodec.VP8, setOf(VideoCodec.H264, VideoCodec.VP8))
        assertEquals(VideoCodec.H264, choice.chosen)
        assertEquals(VideoCodec.VP8, choice.backup)
        assertFalse(choice.substituted)
    }

    @Test
    fun honor_вместо_h264_публикует_vp9_а_не_молчаливый_vp8() {
        val choice = CodecChoice.pick(VideoCodec.H264, VideoCodec.H264, honor)
        assertEquals(VideoCodec.VP9, choice.chosen)
        assertTrue(choice.substituted)
        assertNull(choice.backup, "неумеемый запасной сломал бы вторую дорожку")
    }

    @Test
    fun h265_без_кодера_уходит_в_h264_если_тот_есть() {
        val choice = CodecChoice.pick(VideoCodec.H265, null, setOf(VideoCodec.H264, VideoCodec.VP9))
        assertEquals(VideoCodec.H264, choice.chosen)
    }

    @Test
    fun запасной_совпавший_с_основным_снимается() {
        val choice = CodecChoice.pick(VideoCodec.H265, VideoCodec.VP9, honor)
        assertEquals(VideoCodec.VP9, choice.chosen)
        assertNull(choice.backup)
    }

    @Test
    fun не_узнали_что_умеет_телефон_пресет_не_трогаем() {
        val choice = CodecChoice.pick(VideoCodec.H264, VideoCodec.VP8, emptySet())
        assertEquals(VideoCodec.H264, choice.chosen)
        assertEquals(VideoCodec.VP8, choice.backup)
    }

    @Test
    fun запасной_только_из_тех_что_sdk_посылает() {
        val all = setOf(VideoCodec.H264, VideoCodec.VP9, VideoCodec.H265, VideoCodec.VP8)
        assertNull(CodecChoice.pick(VideoCodec.H264, VideoCodec.VP9, all).backup)
        assertNull(CodecChoice.pick(VideoCodec.H264, VideoCodec.H265, all).backup)
        assertEquals(VideoCodec.VP8, CodecChoice.pick(VideoCodec.VP9, VideoCodec.VP8, all).backup)
        assertNull(CodecChoice.pick(VideoCodec.H264, VideoCodec.VP9, all, exact = true).backup)
    }

    @Test
    fun vp8_последняя_страховка() {
        assertEquals(VideoCodec.VP8, CodecChoice.pick(VideoCodec.H264, null, setOf(VideoCodec.VP8)).chosen)
        assertEquals(
            VideoCodec.VP9,
            CodecChoice.pick(VideoCodec.H264, null, setOf(VideoCodec.VP8, VideoCodec.VP9)).chosen,
        )
    }

    @Test
    fun прогон_стенда_кодек_не_меняет_даже_неумеемый() {
        val choice = CodecChoice.pick(VideoCodec.H264, VideoCodec.VP8, honor, exact = true)
        assertEquals(VideoCodec.H264, choice.chosen, "прогон «H.264» померил бы не H.264")
        assertEquals(VideoCodec.VP8, choice.backup, "прогон запасной пресета не трогает")
        assertFalse(choice.substituted)
    }

    @Test
    fun имена_webrtc_переводятся_в_набор() {
        assertEquals(VideoCodec.H264, CodecChoice.fromWebRtcName("H264"))
        assertEquals(VideoCodec.VP9, CodecChoice.fromWebRtcName("VP9"))
        assertEquals(VideoCodec.H265, CodecChoice.fromWebRtcName("H265"))
        assertEquals(VideoCodec.VP8, CodecChoice.fromWebRtcName("VP8"))
        assertNull(CodecChoice.fromWebRtcName("AV1"))
    }
}
