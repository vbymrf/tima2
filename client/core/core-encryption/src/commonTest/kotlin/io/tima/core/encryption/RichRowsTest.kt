package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupBlock
import io.tima.crypto.MarkupRef
import io.tima.crypto.MarkupWidget
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageSerializer
import io.tima.crypto.proto.MediaRef
import io.tima.domain.chat.RichBlock
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Ряды, ячейки и виджеты (ADR-0044) доходят до экрана моделью, а не текстом узлов: ширины,
 * стиль именами, виджеты по видам, файлы сообщения с ключами.
 */
class RichRowsTest {

    private fun line(content: MessageContent) =
        TextBodyCodec.decodeLine(MessageSerializer.encodeBody(MessageContentCodec.toBody(content)))!!

    @Test
    fun ряд_с_картинкой_и_текстом() {
        val key = ByteArray(32) { 7 }
        val content = MessageContent(
            nodes = listOf("Сбор в 9:00"),
            markup = Markup(
                n = listOf(1),
                blocks = listOf(
                    MarkupBlock(
                        type = BlockType.FLEX,
                        style = mapOf("background" to "amber-soft", "padding" to "small"),
                        children = listOf(
                            MarkupBlock(type = BlockType.CELL, style = mapOf("width" to "40"),
                                children = listOf(MarkupBlock(type = BlockType.IMAGE, widget = MarkupWidget(media = 0)))),
                            MarkupBlock(type = BlockType.CELL, nodeIds = listOf(1), style = mapOf("width" to "60", "valign" to "center")),
                        ),
                    ),
                ),
            ),
            media = listOf(MediaRef(media_id = "m-1", media_key = key.toByteString(), mime = "image/jpeg", width = 640, height = 480)),
        )
        val rich = line(content).rich!!
        val row = assertIs<RichBlock.Row>(rich.blocks.single())
        assertEquals("amber-soft", row.style.background)
        assertEquals(listOf(40, 60), row.cells.map { it.width })
        assertEquals(RichBlock.Image(0), row.cells[0].widgets.single())
        assertEquals("Сбор в 9:00", row.cells[1].text!!.text)
        assertEquals("center", row.cells[1].style.valign)
        assertEquals("m-1", rich.media.single().mediaId)
        assertEquals(key.toList(), rich.media.single().key.toList())
        assertEquals(640, rich.media.single().width)
    }

    @Test
    fun виджеты_по_видам() {
        val ref = MarkupRef(kind = "group", container = "g-1", message = 5)
        val widgets = listOf(
            MarkupBlock(type = BlockType.LINK, widget = MarkupWidget(url = "https://tima.chat/a", mode = "card", title = "Т")),
            MarkupBlock(type = BlockType.MAP, widget = MarkupWidget(lat = 55.75, lon = 37.61, label = "Сбор")),
            MarkupBlock(type = BlockType.CONTACT, widget = MarkupWidget(name = "Аня", phone = "+7")),
            MarkupBlock(type = BlockType.EMOJI, widget = MarkupWidget(text = "🎉")),
            MarkupBlock(type = BlockType.QUOTED, widget = MarkupWidget(ref = ref, author = true)),
        )
        val content = MessageContent(
            nodes = listOf("x"),
            markup = Markup(
                n = listOf(1),
                blocks = listOf(
                    MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(1), style = mapOf("color" to "blue", "align" to "center")),
                    MarkupBlock(type = BlockType.FLEX, children = widgets.take(4).map { MarkupBlock(type = BlockType.CELL, style = mapOf("width" to "25"), children = listOf(it)) }),
                    MarkupBlock(type = BlockType.UNDERLAY, widget = MarkupWidget(ref = ref, lines = 3)),
                ),
            ),
        )
        assertEquals(emptyList(), io.tima.crypto.MarkupRules.check(content, strict = false))
        val rich = line(content).rich!!
        val paragraph = assertIs<RichBlock.Paragraph>(rich.blocks[0])
        assertEquals("blue", paragraph.style.color)
        assertEquals("center", paragraph.style.align)
        val cells = assertIs<RichBlock.Row>(rich.blocks[1]).cells.map { it.widgets.single() }
        assertIs<RichBlock.Link>(cells[0]).also { assertEquals("card", it.mode) }
        assertIs<RichBlock.Place>(cells[1]).also { assertEquals(55.75, it.lat) }
        assertIs<RichBlock.Contact>(cells[2])
        assertIs<RichBlock.Emoji>(cells[3])
        assertEquals(RichBlock.Underlay("group", "g-1", 5, false, 3), rich.blocks[2])
    }
}
