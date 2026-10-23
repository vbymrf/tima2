package io.tima.feature.chat

import io.tima.domain.chat.MediaLinks.Kind
import kotlin.test.Test
import kotlin.test.assertEquals

/** Порядок движков окна (ПЛАН-(ВМ) §3.3, §3.4): по виду ссылки; с каким пошло — тот первым. */
class EngineOrderTest {
    @Test
    fun порядок_по_виду_и_память_по_сайту() {
        assertEquals(listOf(PlayerEngine.OWN, PlayerEngine.WEB), engineOrder(Kind.VIDEO_FILE, null))
        assertEquals(listOf(PlayerEngine.OWN, PlayerEngine.WEB), engineOrder(Kind.RUTUBE, null))
        assertEquals(listOf(PlayerEngine.WEB, PlayerEngine.OWN), engineOrder(Kind.VK_VIDEO, null))
        assertEquals(listOf(PlayerEngine.WEB), engineOrder(Kind.YOUTUBE, null))
        assertEquals(listOf(PlayerEngine.WEB, PlayerEngine.OWN), engineOrder(Kind.VIDEO_FILE, PlayerEngine.WEB))
        assertEquals(listOf(PlayerEngine.WEB), engineOrder(Kind.YOUTUBE, PlayerEngine.OWN), "своего у YouTube нет и памятью не появляется")
    }

    @Test
    fun страница_браузерного_движка_по_ссылке() {
        assertEquals(io.tima.core.media.WebPage.Kind.YOUTUBE, webPageOf("https://youtu.be/dQw4w9WgXcQ?t=5", 5)?.kind)
        assertEquals(io.tima.core.media.WebPage.Kind.VK, webPageOf("https://vkvideo.ru/video-65886767_456239913", 0)?.kind)
        assertEquals(io.tima.core.media.WebPage.Kind.RUTUBE, webPageOf("https://rutube.ru/video/9d606361c5c4d6c7491ac2b6104c6031/", 0)?.kind)
        assertEquals(io.tima.core.media.WebPage.Kind.FILE, webPageOf("https://x.ru/a.webm", 0)?.kind)
        assertEquals(null, webPageOf("https://x.ru/page", 0))
    }
}
