package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Кадр «сообщение удалено у всех» (Решения §8): личная переписка и группа — один вид решения. */
class EventStreamProtocolDeletedTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun личное_и_группа_разбираются() {
        val personal = assertIs<EventStreamProtocol.Decision.MessageDeleted>(
            protocol.decide("""{"event":"message.deleted","event_id":5,"chat_id":"c-1","message_id":42,"by":"u-1"}"""),
        )
        assertEquals("c-1", personal.chatId)
        assertEquals(42L, personal.messageId)
        assertEquals(5L, personal.eventId)
        val group = assertIs<EventStreamProtocol.Decision.MessageDeleted>(
            protocol.decide("""{"event":"message.deleted","event_id":6,"group_id":"g-1","message_id":7}"""),
        )
        assertEquals("g-1", group.chatId)
        assertEquals("", group.by)
    }

    @Test
    fun без_номера_пропускается() {
        assertIs<EventStreamProtocol.Decision.Skip>(protocol.decide("""{"event":"message.deleted","event_id":7,"chat_id":"c-1"}"""))
    }
}
