package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Кадр о сужении круга (ADR-0019 §6, ПЛАН-СОЦИУМА Г7).
 *
 * Без разбора этого кадра сужение выглядело бы для участника поломкой: реплика пропадает
 * из чужих лент, а сказать об этом некому — событие ушло бы в «незнакомый кадр».
 */
class EventStreamProtocolLevelTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun сужение_разбирается_целиком() {
        val decision = protocol.decide(
            """{"event":"message.level_narrowed","event_id":9,"group_id":"g-1","message_id":42,"level":3,"by":"u-7"}""",
        )

        val narrowed = assertIs<EventStreamProtocol.Decision.LevelNarrowed>(decision)
        assertEquals("g-1", narrowed.groupId)
        assertEquals(42L, narrowed.messageId)
        assertEquals(3, narrowed.level)
        assertEquals("u-7", narrowed.by)
        assertEquals(9L, narrowed.eventId)
    }

    @Test
    fun неполный_кадр_пропускается_но_курсор_двигается() {
        // Иначе курсор застрянет на испорченном событии навсегда — правило 3 протокола.
        val decision = protocol.decide(
            """{"event":"message.level_narrowed","event_id":11,"group_id":"g-1"}""",
        )

        assertEquals(11L, assertIs<EventStreamProtocol.Decision.Skip>(decision).eventId)
    }

    @Test
    fun без_имени_сузившего_кадр_всё_равно_годен() {
        // Сервер может не назвать, кто сузил. Врать про «админа» мы не станем, но и терять
        // событие из-за этого нельзя: метку и строку человек получить обязан.
        val decision = protocol.decide(
            """{"event":"message.level_narrowed","event_id":12,"group_id":"g-1","message_id":5,"level":2}""",
        )

        assertEquals("", assertIs<EventStreamProtocol.Decision.LevelNarrowed>(decision).by)
    }
}
