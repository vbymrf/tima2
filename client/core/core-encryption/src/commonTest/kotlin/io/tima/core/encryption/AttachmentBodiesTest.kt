package io.tima.core.encryption

import io.tima.domain.chat.MediaSummary
import io.tima.domain.chat.OutMedia
import io.tima.domain.chat.RichBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Тела вложений (Решения §2–§6) проходят весь конвейер и возвращаются экрану тем, что он рисует:
 * альбом и файл — вне пузыря, карточки — виджетами; список переписок получает метку, а не пустоту.
 */
class AttachmentBodiesTest {

    private val bodies = AttachmentBodiesOverKodium
    private val key = ByteArray(32) { 3 }

    private fun photo(id: String) = OutMedia(id, key, "image/jpeg", 1280, 960, 1000)

    @Test
    fun альбом_доходит_альбомом_с_размерами_и_ключом() {
        val body = bodies.album(listOf(photo("a"), photo("b")))
        val rich = TextBodyCodec.decodeLine(body)!!.rich!!
        assertEquals(RichBlock.Album(listOf(0, 1)), rich.blocks.single())
        assertEquals(listOf("a", "b"), rich.media.map { it.mediaId })
        assertEquals(key.toList(), rich.media[0].key.toList())
        assertEquals(1280 to 960, rich.media[0].width to rich.media[0].height)
        assertEquals(MediaSummary.of(MediaSummary.Kind.PHOTO, 2), TextBodyCodec.decodeText(body))
    }

    @Test
    fun файл_доходит_файлом_с_именем() {
        val body = bodies.file(OutMedia("f", key, "application/pdf", size = 2048, name = "отчёт.pdf"))
        val rich = TextBodyCodec.decodeLine(body)!!.rich!!
        assertEquals(RichBlock.File(0), rich.blocks.single())
        assertEquals("отчёт.pdf", rich.media.single().name)
        assertEquals(2048, rich.media.single().size)
        assertEquals(MediaSummary.of(MediaSummary.Kind.FILE, 1, "отчёт.pdf"), TextBodyCodec.decodeText(body))
    }

    @Test
    fun карточки_доходят_виджетами_и_подписью_в_списке() {
        val cases = listOf(
            bodies.contact("Аня", "+7 900", "") to MediaSummary.of(MediaSummary.Kind.CONTACT, 1, "Аня"),
            bodies.place(55.75, 37.61, "") to MediaSummary.of(MediaSummary.Kind.PLACE, 1, ""),
            bodies.link("https://www.tima.chat/a", "", "") to MediaSummary.of(MediaSummary.Kind.LINK, 1, "tima.chat"),
            bodies.link("https://disk.yandex.ru/d/x", "Смета", "document") to MediaSummary.of(MediaSummary.Kind.LINK, 1, "Смета"),
        )
        for ((body, summary) in cases) {
            val row = assertIs<RichBlock.Row>(TextBodyCodec.decodeLine(body)!!.rich!!.blocks.single())
            assertEquals(100, row.cells.single().width)
            assertEquals(1, row.cells.single().widgets.size)
            assertEquals(summary, TextBodyCodec.decodeText(body))
        }
    }

    @Test
    fun картинки_группы_внутри_сообщения() {
        val body = bodies.inlineImages(listOf(photo("a")))
        val row = assertIs<RichBlock.Row>(TextBodyCodec.decodeLine(body)!!.rich!!.blocks.single())
        assertEquals(RichBlock.Image(0), row.cells.single().widgets.single())
        assertEquals(MediaSummary.of(MediaSummary.Kind.PHOTO, 1), TextBodyCodec.decodeText(body))
    }
}
