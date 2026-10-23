package io.tima.domain.chat

import io.tima.domain.chat.MediaLinks.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Видео и звук узнаются по самой ссылке (ПЛАН-(ВМ) §3.5): домен или окончание файла. */
class MediaLinksTest {
    private val laguna = "https://u-laguna.ru/wp-content/uploads/2026/04/%D0%9C%D0%BE%D0%B1-%D0%94%D0%BE%D1%80%D0%BE%D0%B3%D0%B0.mp4"

    @Test
    fun файлы_по_окончанию() {
        assertEquals(Kind.VIDEO_FILE, MediaLinks.kindOf(laguna))
        assertEquals(Kind.VIDEO_FILE, MediaLinks.kindOf("https://x.ru/a/clip.WEBM?token=1"))
        assertEquals(Kind.AUDIO_FILE, MediaLinks.kindOf("https://x.ru/podcast/ep1.mp3"))
        assertEquals(Kind.STREAM, MediaLinks.kindOf("https://radio.ru/live/index.m3u8"))
        assertNull(MediaLinks.kindOf("https://x.ru/page.html"))
        assertNull(MediaLinks.kindOf("ftp://x.ru/a.mp4"))
        assertNull(MediaLinks.kindOf("https://x.ru/"))
    }

    @Test
    fun сайты_по_домену_и_номеру_ролика() {
        assertEquals(Kind.YOUTUBE, MediaLinks.kindOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"))
        assertEquals("dQw4w9WgXcQ", MediaLinks.youtubeId("https://youtu.be/dQw4w9WgXcQ?t=10"))
        assertEquals("dQw4w9WgXcQ", MediaLinks.youtubeId("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
        assertNull(MediaLinks.kindOf("https://www.youtube.com/channel/abc"))
        assertEquals(Kind.RUTUBE, MediaLinks.kindOf("https://rutube.ru/video/9d606361c5c4d6c7491ac2b6104c6031/?r=plwd"))
        assertEquals("9d606361c5c4d6c7491ac2b6104c6031", MediaLinks.rutubeId("https://rutube.ru/play/embed/9d606361c5c4d6c7491ac2b6104c6031"))
        assertEquals(Kind.VK_VIDEO, MediaLinks.kindOf("https://vkvideo.ru/video-65886767_456239913"))
        assertEquals("-65886767" to "456239913", MediaLinks.vkVideo("https://vkvideo.ru/video_ext.php?oid=-65886767&id=456239913&hash="))
        assertNull(MediaLinks.kindOf("https://vk.com/id1"))
    }

    @Test
    fun имя_файла_и_секунда_из_адреса() {
        assertEquals("Моб-Дорога", MediaLinks.fileName(laguna))
        assertEquals("ep1", MediaLinks.fileName("https://x.ru/podcast/ep1.mp3?x=1"))
        assertEquals(42, MediaLinks.startSeconds("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42"))
        assertEquals(90, MediaLinks.startSeconds("https://youtu.be/dQw4w9WgXcQ?t=1m30s"))
        assertNull(MediaLinks.startSeconds("https://youtu.be/dQw4w9WgXcQ"))
    }
}
