package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Кадр «копия аккаунта изменилась» (ПЛАН-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ9): вид и ревизия —
 * всё, что клиенту нужно, чтобы решить, забирать ли копию.
 */
class StoreChangedEventTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun изменение_копии_разбирается_в_свой_кадр() {
        val decision = protocol.decide("""{"event":"store.changed","event_id":51,"kind":"reads","revision":4}""")
        assertTrue(decision is EventStreamProtocol.Decision.StoreChanged, "разобрано как $decision")
        assertEquals("reads", decision.kind)
        assertEquals(4, decision.revision)
        assertEquals(51, decision.eventId)
    }

    @Test
    fun неполный_кадр_пропускается_но_курсор_двигается() {
        val decision = protocol.decide("""{"event":"store.changed","event_id":52,"kind":"book"}""")
        assertTrue(decision is EventStreamProtocol.Decision.Skip, "разобрано как $decision")
        assertEquals(52, decision.eventId)
    }
}
