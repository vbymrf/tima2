package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Время приёма сервером (`received_at_unix_ms`, беда 2026-10-29): по нему сообщение встаёт на место; без него —
 * прежнее время написания из подписи. Подписанные поля при этом не трогаются.
 */
class ReceivedAtTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun личное_событие_несёт_время_приёма() {
        val frame = """{"event":"message.new","event_id":5,"chat_id":"c-1","message_id":77,""" +
            """"envelope":"${encodeBase64Url(byteArrayOf(1, 2))}","received_at_unix_ms":1793300018332}"""
        val decision = assertIs<EventStreamProtocol.Decision.Deliver>(protocol.decide(frame))
        assertEquals(1_793_300_018_332, decision.event.receivedAtMs)
    }

    @Test
    fun у_старого_сервера_времени_приёма_нет() {
        val frame = """{"event":"message.new","event_id":5,"chat_id":"c-1","message_id":77,""" +
            """"envelope":"${encodeBase64Url(byteArrayOf(1, 2))}"}"""
        val decision = assertIs<EventStreamProtocol.Decision.Deliver>(protocol.decide(frame))
        assertEquals(0, decision.event.receivedAtMs)
    }

    @Test
    fun кадр_группы_встаёт_по_приёму_а_без_него_по_написанию() {
        val base = """{"event":"message.group","event_id":5,"group_id":"g-1","message_id":77,"sender_id":"u-2",""" +
            """"sender_device":"dev-2","kind":1,"gk_version":3,"payload":"AQID","signature":"BAUG",""" +
            """"created_at_unix_ms":1793300297710,"thread_root":0,"reply_to":0"""
        val withServer = assertIs<EventStreamProtocol.Decision.Deliver>(protocol.decide("$base,\"received_at_unix_ms\":1793300018332}"))
        val parsed = assertNotNull(GroupFrame.parse(withServer.event.envelope))
        assertEquals(1_793_300_297_710, parsed.createdAtUnixMs, "подписанное время не меняется")
        assertEquals(1_793_300_018_332, parsed.placedAtMs)

        val old = assertIs<EventStreamProtocol.Decision.Deliver>(protocol.decide("$base}"))
        assertEquals(1_793_300_297_710, assertNotNull(GroupFrame.parse(old.event.envelope)).placedAtMs)
    }
}
