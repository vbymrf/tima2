package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Кадры почтальона (Решения §6): просьба загрузить снова и «получатель забрал». */
class EventStreamProtocolMediaTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun просьба_и_забрал_разбираются() {
        val wanted = assertIs<EventStreamProtocol.Decision.MediaEvent>(
            protocol.decide("""{"event":"media.wanted","event_id":3,"media_id":"m-1","chat_id":"c-1","by_device":"d-2"}"""),
        )
        assertTrue(wanted.wanted)
        assertEquals("m-1", wanted.mediaId)
        assertEquals(3L, wanted.eventId)
        val fetched = assertIs<EventStreamProtocol.Decision.MediaEvent>(
            protocol.decide("""{"event":"media.fetched","media_id":"m-2","chat_id":"c-1"}"""),
        )
        assertFalse(fetched.wanted)
        assertIs<EventStreamProtocol.Decision.Skip>(protocol.decide("""{"event":"media.wanted","chat_id":"c-1"}"""))
    }
}
