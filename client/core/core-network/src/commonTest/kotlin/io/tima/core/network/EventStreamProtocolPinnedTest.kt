package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Кадр «закрепили» (ПЛАН-(ЗО) ЗО1): личная переписка и группа; 0 — сняли. */
class EventStreamProtocolPinnedTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun личное_и_группа_разбираются() {
        val personal = assertIs<EventStreamProtocol.Decision.MessagePinned>(
            protocol.decide("""{"event":"message.pinned","event_id":5,"chat_id":"c-1","message_id":42,"by":"u-1"}"""),
        )
        assertEquals("c-1", personal.chatId)
        assertEquals(42L, personal.messageId)
        assertEquals("u-1", personal.by)
        val unpinned = assertIs<EventStreamProtocol.Decision.MessagePinned>(
            protocol.decide("""{"event":"message.pinned","event_id":6,"group_id":"g-1","message_id":0}"""),
        )
        assertEquals("g-1", unpinned.chatId)
        assertEquals(0L, unpinned.messageId)
    }

    @Test
    fun список_закреплённых_разбирается_прежнее_поле_на_месте() {
        // ПЛАН-(СЦ) СЦ12: сервер шлёт и номера списком, и название, и что изменилось; прежнее message_id — новейшее.
        val d = assertIs<EventStreamProtocol.Decision.MessagePinned>(
            protocol.decide("""{"event":"message.pinned","event_id":8,"group_id":"g-1","message_id":12,"message_ids":[12,7],"title":"Важное","change":"added"}"""),
        )
        assertEquals(12L, d.messageId)
        assertEquals(listOf(12L, 7L), d.ids)
        assertEquals("Важное", d.title)
        assertEquals("added", d.change)
        val old = assertIs<EventStreamProtocol.Decision.MessagePinned>(
            protocol.decide("""{"event":"message.pinned","event_id":9,"chat_id":"c-1","message_id":3}"""),
        )
        assertEquals(null, old.ids, "прежний сервер — списка нет")
    }

    @Test
    fun без_номера_пропускается() {
        assertIs<EventStreamProtocol.Decision.Skip>(protocol.decide("""{"event":"message.pinned","event_id":7,"chat_id":"c-1"}"""))
    }
}
