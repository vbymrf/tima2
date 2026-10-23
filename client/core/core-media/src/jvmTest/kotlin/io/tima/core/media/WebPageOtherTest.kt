package io.tima.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Уход проигрывателя сайта с нашего ролика (заказчик 2026-10-23, 4а): что считается другим видео. */
class WebPageOtherTest {
    private val rutube = WebPages.rutube("https://rutube.ru/video/9d606361c5c4d6c7491ac2b6104c6031/", "9d606361c5c4d6c7491ac2b6104c6031", 0)

    @Test
    fun другой_ролик_того_же_сайта_чужой_а_реклама_и_наш_нет() {
        assertTrue(rutube.isOther("https://rutube.ru/play/embed/0123456789abcdef0123456789abcdef"))
        assertTrue(rutube.isOther("https://rutube.ru/channel/23460655/"))
        assertFalse(rutube.isOther("https://rutube.ru/play/embed/9d606361c5c4d6c7491ac2b6104c6031?autoplay=true"))
        assertFalse(rutube.isOther("https://ads.example.com/frame.html"))
        assertFalse(WebPages.file("https://x.ru/a.mp4", 0).isOther("https://x.ru/b.html"), "у файла своего сайта нет")
    }

    @Test
    fun мост_away_зажигает_ушёл() {
        val s = OwnPlayerState(positionMs = 5_000).bridged("""{"t":"away"}""")
        assertTrue(s.elsewhere)
        assertEquals(5_000, s.positionMs)
    }
}
