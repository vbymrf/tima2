package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/** «Содержимое» (ПЛАН-(СЦ) СЦ20): вложения сообщения раскладываются по подразделам. */
class ContentIndexTest {

    private fun media(id: String, mime: String, voice: Boolean = false, name: String = "") =
        RichMedia(id, ByteArray(0), mime, 10, 10, name = name, size = 5, voice = voice)

    @Test
    fun фото_видео_музыка_голосовое_файл_по_своим_подразделам() {
        val rich = RichContent(
            blocks = listOf(RichBlock.Album(listOf(0, 1)), RichBlock.Voice(2), RichBlock.File(3), RichBlock.File(4)),
            media = listOf(
                media("p", "image/jpeg"),
                media("v", "video/mp4"),
                media("g", "audio/ogg", voice = true),
                media("m", "audio/mpeg", name = "песня.mp3"),
                media("f", "application/pdf", name = "отчёт.pdf"),
            ),
        )
        val items = ContentIndex.of(7, 100, rich, null)
        assertEquals(
            listOf(ContentSection.Photo, ContentSection.Video, ContentSection.Voice, ContentSection.Music, ContentSection.File),
            items.map { it.section },
        )
        assertEquals("отчёт.pdf", items.last().name)
        assertEquals(listOf(0, 1, 2, 3, 4), items.map { it.index })
    }

    @Test
    fun картинка_карточки_ссылки_не_фото_а_ссылка_одна() {
        val rich = RichContent(
            blocks = listOf(RichBlock.Link("https://tima.example/a", "card", "Заголовок", "", media = 0)),
            media = listOf(media("c", "image/png")),
        )
        val items = ContentIndex.of(1, 1, rich, "смотри https://tima.example/a и https://другое.рф/путь.")
        assertEquals(listOf(ContentSection.Link, ContentSection.Link), items.map { it.section })
        assertEquals("https://tima.example/a", items[0].url)
        assertEquals("Заголовок", items[0].title)
        assertEquals("https://другое.рф/путь", items[1].url)
    }

    @Test
    fun простой_текст_без_адресов_ничего_не_даёт() {
        assertEquals(emptyList(), ContentIndex.of(1, 1, null, "просто текст"))
    }
}
