package io.tima.core.encryption

import io.tima.crypto.BlockType
import io.tima.crypto.Markup
import io.tima.crypto.MarkupBlock
import io.tima.crypto.MarkupRules
import io.tima.crypto.MarkupWidget
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageSerializer
import io.tima.crypto.proto.MediaRef
import io.tima.domain.chat.RichBlock
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Проверка нагрузки (Решения §14): сообщение 20 рядов × 4 ячейки с картинками — разбор тела в
 * модель экрана укладывается в кадр с большим запасом. Картинок — 10, предел разметки; остальные
 * ячейки — текст. Живой замер прокрутки на Redmi — ЖУРНАЛ-РАБОТ, Н8.
 */
class HeavyMessageTest {

    private fun heavy(): MessageContent {
        val nodes = mutableListOf<String>()
        var images = 0
        val rows = (0 until 20).map { r ->
            MarkupBlock(
                type = BlockType.FLEX,
                children = (0 until 4).map { c ->
                    if (images < 10 && c == 0) {
                        val index = images++
                        MarkupBlock(type = BlockType.CELL, style = mapOf("width" to "25"),
                            children = listOf(MarkupBlock(type = BlockType.IMAGE, widget = MarkupWidget(media = index))))
                    } else {
                        nodes += "ряд $r, ячейка $c — текст ячейки"
                        MarkupBlock(type = BlockType.CELL, nodeIds = listOf(nodes.size), style = mapOf("width" to "25", "background" to "amber-soft"))
                    }
                },
            )
        }
        return MessageContent(
            nodes = nodes,
            markup = Markup(n = Markup.idsFor(nodes.size), blocks = rows),
            media = (0 until 10).map { MediaRef(media_id = "m-$it", media_key = ByteArray(32).toByteString(), mime = "image/jpeg", width = 320, height = 240) },
        )
    }

    @Test
    fun двадцать_рядов_по_четыре_разбираются_быстрее_кадра() {
        val content = heavy()
        assertEquals(emptyList(), MarkupRules.check(content, strict = true))
        val body = MessageSerializer.encodeBody(MessageContentCodec.toBody(content))
        repeat(10) { TextBodyCodec.decodeLine(body) } // прогрев JIT
        val runs = 30
        val took = measureTime { repeat(runs) { TextBodyCodec.decodeLine(body) } } / runs
        println("Тяжёлое сообщение (${body.size} байт тела): разбор ${took.inWholeMicroseconds} мкс")
        val rows = TextBodyCodec.decodeLine(body)!!.rich!!.blocks.filterIsInstance<RichBlock.Row>()
        assertEquals(20, rows.size)
        assertTrue(took.inWholeMilliseconds < 16, "разбор ${took.inWholeMilliseconds} мс — дольше кадра")
    }
}
