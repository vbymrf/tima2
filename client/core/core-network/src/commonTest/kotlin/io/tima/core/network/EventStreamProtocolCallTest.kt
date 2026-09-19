package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Кадры звонка (`call.incoming`, `call.state`).
 *
 * До 2026-09-19 канал их не разбирал вовсе: они уходили в «незнакомый кадр», и **входящий
 * звонок дойти до человека не мог никаким образом**. Сервер их слал с самого начала.
 */
class EventStreamProtocolCallTest {

    private val protocol = EventStreamProtocol()

    @Test
    fun входящий_разбирается_целиком() {
        val decision = protocol.decide(
            """{"event":"call.incoming","event_id":11,"call_id":"c-1","room":"call-abc","kind":"video","from":"u-7"}""",
        )
        val call = assertIs<EventStreamProtocol.Decision.CallIncoming>(decision)
        assertEquals("c-1", call.callId)
        assertEquals("call-abc", call.room)
        assertEquals("video", call.kind)
        assertEquals("u-7", call.from)
        assertEquals(11L, call.eventId)
    }

    @Test
    fun вид_по_умолчанию_звук_а_не_видео() {
        // Ошибка в эту сторону дешевле: включить камеру человек успеет, выключить
        // внезапно включившуюся — уже нет.
        val decision = protocol.decide(
            """{"event":"call.incoming","event_id":12,"call_id":"c-2","room":"call-b"}""",
        )
        assertEquals("audio", assertIs<EventStreamProtocol.Decision.CallIncoming>(decision).kind)
    }

    @Test
    fun входящий_без_комнаты_пропускается() {
        // Без комнаты звонок никуда не ведёт. Показать его человеку — обещать разговор,
        // который не начнётся.
        val decision = protocol.decide("""{"event":"call.incoming","event_id":13,"call_id":"c-3"}""")
        val skip = assertIs<EventStreamProtocol.Decision.Skip>(decision)
        assertEquals(13L, skip.eventId, "курсор обязан двинуться даже на пропущенном кадре")
    }

    @Test
    fun состояние_звонка_приходит_словом_сервера() {
        // Своего перечня состояний не заводим: сервер знает их случаи лучше, а свой
        // перечень обещал бы, что мы перечислили все.
        val decision = protocol.decide(
            """{"event":"call.state","event_id":14,"call_id":"c-1","state":"answered"}""",
        )
        val state = assertIs<EventStreamProtocol.Decision.CallState>(decision)
        assertEquals("c-1", state.callId)
        assertEquals("answered", state.state)
        assertEquals(14L, state.eventId)
    }

    @Test
    fun состояние_без_поля_state_пропускается() {
        val decision = protocol.decide("""{"event":"call.state","event_id":15,"call_id":"c-1"}""")
        assertIs<EventStreamProtocol.Decision.Skip>(decision)
    }
}
