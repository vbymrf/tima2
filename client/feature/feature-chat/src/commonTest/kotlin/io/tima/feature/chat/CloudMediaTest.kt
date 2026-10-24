package io.tima.feature.chat

import io.tima.domain.chat.ComposedDoc
import io.tima.domain.chat.ComposedItem
import io.tima.domain.chat.ComposedWidget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** «Облако» с видом «Видео» или «Аудио» — нашим виджетом, когда ссылка играет (заказчик 2026-10-24). */
class CloudMediaTest {
    private val empty = ComposedDoc()

    @Test
    fun видео_и_аудио_которые_играют_становятся_нашими_виджетами() {
        val video = assertIs<ComposedWidget.Video>(cloudMediaWidget("https://x.ru/clips/day.mp4", "День", "video", empty))
        assertEquals("День", video.description)
        val audio = assertIs<ComposedWidget.Audio>(cloudMediaWidget("https://x.ru/a/song%201.mp3", "", "audio", empty))
        assertEquals("song 1", audio.description, "без названия — имя файла")
        assertIs<ComposedWidget.Video>(cloudMediaWidget("https://rutube.ru/video/9d606361c5c4d6c7491ac2b6104c6031/", "Ролик", "video", empty))
    }

    @Test
    fun страница_диска_и_чужой_вид_остаются_облаком() {
        assertNull(cloudMediaWidget("https://disk.yandex.ru/i/abc", "Фильм", "video", empty), "страница диска не играет")
        assertNull(cloudMediaWidget("https://x.ru/a/song.mp3", "Песня", "video", empty), "звук видом «Видео» — облаком")
        assertNull(cloudMediaWidget("https://x.ru/clips/day.mp4", "День", "file", empty))
    }

    @Test
    fun описание_не_совпадает_с_уже_вставленным() {
        val doc = ComposedDoc(listOf(ComposedItem.Widget(ComposedWidget.Video("https://x.ru/1.mp4", "День"))))
        val second = assertIs<ComposedWidget.Video>(cloudMediaWidget("https://x.ru/2.mp4", "День", "video", doc))
        assertEquals(false, second.description == "День")
    }

    @Test
    fun скрепка_из_облака_файл_облаком_видео_и_звук_виджетом() {
        val file = assertIs<ComposedWidget.Cloud>(cloudAttachWidget("x.ru/docs/%D0%BE%D1%82%D1%87%D1%91%D1%82.pdf", "", "file"))
        assertEquals("https://x.ru/docs/%D0%BE%D1%82%D1%87%D1%91%D1%82.pdf", file.url, "без схемы — https")
        assertEquals("отчёт.pdf", file.title, "без названия — имя файла")
        assertEquals("file", file.kind)
        assertEquals("День", assertIs<ComposedWidget.Video>(cloudAttachWidget("https://x.ru/day.mp4", "День", "video")).description)
        assertIs<ComposedWidget.Audio>(cloudAttachWidget("https://x.ru/song.mp3", "", "audio"))
        val page = assertIs<ComposedWidget.Cloud>(cloudAttachWidget("https://disk.yandex.ru/i/abc", "Фильм", "video"))
        assertEquals("video", page.kind, "не играет — облаком с видом")
        assertEquals("Фильм", page.title)
    }
}
