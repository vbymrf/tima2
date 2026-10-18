package io.tima.core.encryption

import io.tima.crypto.Markup
import io.tima.crypto.MarkupBlock
import io.tima.crypto.BlockType
import io.tima.crypto.MessageContent
import io.tima.crypto.MessageContentCodec
import io.tima.crypto.MessageSerializer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Показ без оформления сообщается журналу правилами, без текста (ПЛАН-(ЖС) §2, MSG-MARKUP). */
class CodecWatchTest {

    @AfterTest
    fun reset() {
        CodecWatch.markupDropped = null
    }

    @Test
    fun разметка_со_ссылкой_на_несуществующий_узел_показывается_текстом_и_сообщается() {
        val rules = mutableListOf<String>()
        CodecWatch.markupDropped = { r, bytes -> rules += r; assertTrue(bytes > 0) }
        val broken = MessageContent(
            nodes = listOf("текст"),
            markup = Markup(n = listOf(0), blocks = listOf(MarkupBlock(type = BlockType.PARAGRAPH, nodeIds = listOf(9)))),
        )
        val body = MessageSerializer.encodeBody(MessageContentCodec.toBody(broken))
        val line = TextBodyCodec.decodeLine(body)!!
        assertEquals(null, line.rich, "без оформления")
        assertEquals(listOf("node-ref"), rules)
    }
}
