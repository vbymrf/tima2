package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Кадры про групповые ключи.
 *
 * До этой правки клиент их **молча пропускал**: ротация ключа, приезд недостающих
 * обёрток и просьба поделиться попадали в ветку «незнакомый кадр». Внешне это выглядело
 * исправно — канал работал, сообщения шли, — а группы при этом тихо переставали читаться.
 */
class EventStreamProtocolKeysTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun ротация_и_приезд_обёрток_означают_одно() {
        // Оба кадра требуют одного действия — сходить за обёртками. Различать их значило
        // бы завести два пути к одной работе, и они разошлись бы при первой правке.
        val ротация = protocol.decide("""{"event":"key.rotated","event_id":7,"group_id":"g-1","gk_version":3}""")
        val приезд = protocol.decide("""{"event":"recovery.gk_ready","event_id":8,"group_id":"g-1"}""")

        assertEquals("g-1", assertIs<EventStreamProtocol.Decision.KeysArrived>(ротация).groupId)
        assertEquals(7L, (ротация as EventStreamProtocol.Decision.KeysArrived).eventId)
        assertIs<EventStreamProtocol.Decision.KeysArrived>(приезд)
    }

    @Test
    fun просьба_поделиться_разбирается_целиком() {
        val кадр = """{"event":"recovery.gk_request","event_id":9,"group_id":"g-1",""" +
            """"requester_device":"dev-2","requester_enc_pub":"AAAA","versions":[1,2,3]}"""

        val решение = assertIs<EventStreamProtocol.Decision.ShareKeys>(protocol.decide(кадр))
        assertEquals("dev-2", решение.requesterDevice)
        assertContentEquals(listOf(1, 2, 3), решение.versions)
        assertEquals(9L, решение.eventId)
    }

    @Test
    fun просьба_без_версий_пропускается_но_подтверждается() {
        // Курсор обязан двигаться даже на испорченном кадре — иначе он застрянет на нём
        // навсегда, и всё, что после, не приедет никогда.
        val решение = protocol.decide(
            """{"event":"recovery.gk_request","event_id":10,"group_id":"g-1","requester_device":"dev-2"}""",
        )
        assertEquals(10L, assertIs<EventStreamProtocol.Decision.Skip>(решение).eventId)
    }

    @Test
    fun просьба_ротировать_несёт_причину() {
        val решение = assertIs<EventStreamProtocol.Decision.RotationNeeded>(
            protocol.decide("""{"event":"group.rotation_needed","event_id":11,"group_id":"g-1","reason":"epoch"}"""),
        )
        assertEquals("epoch", решение.reason)
    }

    @Test
    fun причина_по_умолчанию_эпоха() {
        // Сервер шлёт это событие и при отзыве устройства, но причина у него всегда есть.
        // Отсутствие — признак старого сервера, и «epoch» здесь безопаснее молчания:
        // ротация всё равно нужна.
        val решение = assertIs<EventStreamProtocol.Decision.RotationNeeded>(
            protocol.decide("""{"event":"group.rotation_needed","event_id":12,"group_id":"g-1"}"""),
        )
        assertEquals("epoch", решение.reason)
    }
}
