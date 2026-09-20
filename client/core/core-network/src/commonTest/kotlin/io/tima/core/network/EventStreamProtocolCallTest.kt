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
    fun уход_участника_разбирается() {
        // До 2026-09-20 кадр не разбирался вовсе и уходил в «незнакомый». Так кончается
        // звонок, у которого вторую сторону убили: нажимать «Завершить» там некому, и
        // `call.state ended` не приходит — сервер шлёт именно этот кадр.
        val decision = protocol.decide(
            """{"event":"call.participant_left","event_id":21,"call_id":"c-1","user_id":"u-7"}""",
        )
        val left = assertIs<EventStreamProtocol.Decision.CallLeft>(decision)
        assertEquals("c-1", left.callId)
        assertEquals("u-7", left.userId)
        assertEquals(21L, left.eventId)
    }

    @Test
    fun уход_без_участника_пропускается() {
        // Без идентификатора неизвестно, чей уход: в группе он не кончает разговор, и
        // принять его за конец значило бы обрывать звонок на чужом выходе.
        val decision = protocol.decide("""{"event":"call.participant_left","event_id":22,"call_id":"c-1"}""")
        val skip = assertIs<EventStreamProtocol.Decision.Skip>(decision)
        assertEquals(22L, skip.eventId, "курсор обязан двинуться даже на пропущенном кадре")
    }

    @Test
    fun вызов_никем_не_забранный_приходит_отдельным_кадром() {
        // Отдельным, а не словом в call.state: тот по правилу «от обратного» кончил бы
        // звонок (ADR-0025, решение 2), а устройство собеседника может вернуться и взять
        // вызов из журнала — сорок пять секунд ещё не вышли.
        val decision = protocol.decide("""{"event":"call.unreachable","event_id":31,"call_id":"c-1"}""")
        val слово = assertIs<EventStreamProtocol.Decision.CallUnreachable>(decision)
        assertEquals("c-1", слово.callId)
        assertEquals(31L, слово.eventId)
    }

    @Test
    fun вызов_без_идентификатора_пропускается() {
        // Неизвестно, о каком звонке речь: у человека может идти один разговор и звонить
        // другой номер, и слово не о том звонке хуже молчания.
        val decision = protocol.decide("""{"event":"call.unreachable","event_id":32}""")
        val skip = assertIs<EventStreamProtocol.Decision.Skip>(decision)
        assertEquals(32L, skip.eventId, "курсор обязан двинуться даже на пропущенном кадре")
    }

    @Test
    fun состояние_без_поля_state_пропускается() {
        val decision = protocol.decide("""{"event":"call.state","event_id":15,"call_id":"c-1"}""")
        assertIs<EventStreamProtocol.Decision.Skip>(decision)
    }
}
