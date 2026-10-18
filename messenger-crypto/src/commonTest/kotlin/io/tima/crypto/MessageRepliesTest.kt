package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ответ — блок `reply` первым (ADR-0044): узлы не сдвигаются, правила его пропускают. */
class MessageRepliesTest {

    private val reply = MessageReplies.Reply(MarkupRef(kind = "group", container = "g-1", message = 42), userId = "u-7")

    @Test
    fun простой_текст_с_ответом_и_обратно() {
        val plain = MessageContent.text("да, в девять")
        val withReply = MessageReplies.attach(plain, reply)
        assertEquals(reply, MessageReplies.of(withReply))
        assertTrue(MarkupRules.check(withReply, strict = true).isEmpty(), MarkupRules.check(withReply, strict = true).toString())
        assertEquals("да, в девять", withReply.displayText())
        assertEquals(plain, MessageReplies.detach(withReply))
        assertNull(MessageReplies.of(plain))
    }

    @Test
    fun оформленное_сохраняет_блоки_и_узлы() {
        val typed = QuickMarkdown.parse("# Итог\n**да**")
        val withReply = MessageReplies.attach(typed, reply)
        assertEquals(typed.nodes, withReply.nodes)
        assertEquals(typed.markup!!.blocks, withReply.markup!!.blocks.drop(1))
        assertEquals(typed, MessageReplies.detach(withReply))
        // Круг через тело: разметка разбирается обратно с тем же ответом.
        val back = MessageContentCodec.fromBody(MessageContentCodec.toBody(withReply))
        assertEquals(reply, MessageReplies.of(back))
    }
}
