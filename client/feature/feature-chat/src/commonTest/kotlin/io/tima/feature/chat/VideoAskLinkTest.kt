package io.tima.feature.chat

import io.tima.domain.chat.MediaLinks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Форма «Видео» и ссылка, которая в окне TIMA не играет (заказчик 2026-10-23, Samsung, сообщение 21:34). */
class VideoAskLinkTest {
    @Test
    fun страница_автора_rutube_не_видео_и_уходит_кнопкой_с_описанием() {
        val page = "https://rutube.ru/video/person/64804659/"
        assertFalse(MediaLinks.playsHere(page), "страница автора — не ролик")
        val link = videoAskLink(page, "  Маша и медведь ")
        assertEquals(page, link.url)
        assertEquals("button", link.mode)
        assertEquals("Маша и медведь", link.title)
    }
}
