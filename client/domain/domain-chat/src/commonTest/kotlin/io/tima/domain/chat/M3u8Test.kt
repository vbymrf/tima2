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
    fun качества_по_высоте_сверху_лучшее_одна_высота_один_вариант() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
            #EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=9000000,BANDWIDTH=2500000,RESOLUTION=1280x720
            https://cdn.r.ru/720.m3u8?s=1
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720
            720-low.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
            audio.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            /hd/1080.m3u8
        """.trimIndent()
        assertEquals(
            listOf(
                M3u8.Variant(1080, "https://r.ru/hd/1080.m3u8"),
                M3u8.Variant(720, "https://cdn.r.ru/720.m3u8?s=1"),
                M3u8.Variant(360, "https://r.ru/radio/360/index.m3u8"),
            ),
            M3u8.variants(master, base),
        )
        assertEquals(emptyList(), M3u8.variants("#EXTM3U\n#EXTINF:10.0,\na.ts", base), "список кусков — не выбор качества")
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
