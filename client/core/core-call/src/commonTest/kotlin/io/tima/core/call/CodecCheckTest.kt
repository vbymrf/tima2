package io.tima.core.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Уходит ли просимый кодек (заказчик 2026-09-29: Samsung просил VP9, слал VP8). */
class CodecCheckTest {

    @Test
    fun просили_vp9_уходит_vp8_два_опроса_подряд() {
        val check = CodecCheck()
        assertNull(check.next(VideoCodec.VP9, null, "video/VP8"), "один опрос — ещё не беда")
        assertEquals(CodecCheck.Mismatch("VP9", "VP8"), check.next(VideoCodec.VP9, null, "video/VP8"))
    }

    @Test
    fun совпадает_или_видео_не_уходит_беды_нет() {
        val check = CodecCheck()
        repeat(3) { assertNull(check.next(VideoCodec.H264, null, "video/H264")) }
        repeat(3) { assertNull(check.next(VideoCodec.H264, null, null)) }
    }

    @Test
    fun запасной_уходит_законно() {
        val check = CodecCheck()
        repeat(3) { assertNull(check.next(VideoCodec.H264, VideoCodec.VP8, "video/VP8")) }
    }

    @Test
    fun сошлось_после_расхождения_беда_снята() {
        val check = CodecCheck()
        check.next(VideoCodec.VP9, null, "video/VP8")
        check.next(VideoCodec.VP9, null, "video/VP8")
        assertNull(check.next(VideoCodec.VP9, null, "video/VP9"))
        assertNull(check.next(VideoCodec.VP9, null, "video/VP8"), "счёт начался заново")
    }

    @Test
    fun h265_в_статистике_называется_и_hevc() {
        val check = CodecCheck()
        repeat(3) { assertNull(check.next(VideoCodec.H265, null, "video/HEVC")) }
    }
}
