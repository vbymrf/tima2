package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Кадр «под вашей записью ответили» (ПЛАН-КАНАЛОВ К8, ADR-0024, следствие 5).
 *
 * Разбор проверяется отдельно от канала: кадр приходит от сервера, и всё, что клиент о
 * нём знает, — эти четыре поля.
 */
class CommentEventTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun комментарий_разбирается_в_свой_кадр() {
        val decision = protocol.decide(
            """{"event":"channel.comment","event_id":42,"channel_id":"ch-1","post_id":7,""" +
                """"comment_id":9,"author_id":"u-2","text":"а что это"}""",
        )
        assertTrue(decision is EventStreamProtocol.Decision.CommentArrived, "разобрано как $decision")
        assertEquals("ch-1", decision.channelId)
        assertEquals(7, decision.postId)
        assertEquals(9, decision.commentId)
        assertEquals("u-2", decision.authorId)
        assertEquals(42, decision.eventId)
    }

    @Test
    fun неполный_кадр_пропускается_но_курсор_двигается() {
        // Кадр нашего вида без обязательных полей: показывать нечего, а курсор двигать
        // надо — иначе он застрянет на испорченном событии навсегда.
        val decision = protocol.decide("""{"event":"channel.comment","event_id":43}""")
        assertTrue(decision is EventStreamProtocol.Decision.Skip, "разобрано как $decision")
        assertEquals(43, decision.eventId)
    }
}
