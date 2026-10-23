package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** m3u8 при вставке (ПЛАН-(ВМ) Р34–Р36): видео — «Видео», запись — «Аудио», эфир — «Поток». */
class M3u8Test {
    private val base = "https://r.ru/radio/index.m3u8?t=1"

    @Test
    fun видео_по_кодеку_или_разрешению() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.42c01f,mp4a.40.2"
            360/index.m3u8
        """.trimIndent()
        val info = M3u8.parse(master, base)!!
        assertEquals(MediaLinks.Kind.VIDEO_FILE, M3u8.widgetOf(info))
        assertEquals("https://r.ru/radio/360/index.m3u8", info.firstVariant)
    }

    @Test
    fun звук_с_концом_запись_без_конца_эфир() {
        val vod = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\na.aac\n#EXTINF:5.5,\nb.aac\n#EXT-X-ENDLIST"
        val live = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:120\n#EXTINF:10.0,\na.aac"
        val v = M3u8.parse(vod, base)!!
        assertEquals(MediaLinks.Kind.AUDIO_FILE, M3u8.widgetOf(v))
        assertEquals(15_500L, v.durationMs)
        assertEquals(MediaLinks.Kind.STREAM, M3u8.widgetOf(M3u8.parse(live, base)!!))
        // Главный без видео — по варианту.
        val master = M3u8.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS=\"mp4a.40.2\"\n/a/live.m3u8", base)!!
        assertEquals("https://r.ru/a/live.m3u8", master.firstVariant)
        assertEquals(MediaLinks.Kind.AUDIO_FILE, M3u8.widgetOf(master, v))
        assertEquals(MediaLinks.Kind.STREAM, M3u8.widgetOf(master, M3u8.parse(live, base)))
    }

    @Test
    fun не_m3u8() {
        assertEquals(null, M3u8.parse("<html>", base))
    }
}
