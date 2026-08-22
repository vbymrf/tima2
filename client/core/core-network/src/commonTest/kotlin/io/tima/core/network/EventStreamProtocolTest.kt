package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Разбор кадров живого канала. Формы — из `internal/api/ws.go` дословно.
 */
class EventStreamProtocolTest {

    private val protocol = EventStreamProtocol()
    private val конверт = byteArrayOf(1, 2, 3, 4)

    private fun кадрСообщения(
        eventId: Long = 5,
        chatId: String = "chat-1",
        messageId: Long = 77,
    ) = """{"event":"message.new","event_id":$eventId,"chat_id":"$chatId",""" +
        """"message_id":$messageId,"envelope":"${encodeBase64Url(конверт)}"}"""

    // ── кадры, которые мы отправляем ─────────────────────────────────────────

    @Test
    fun первый_кадр_это_токен() {
        // Сервер рвёт соединение с StatusPolicyViolation, если первым пришло что-то
        // другое. Значит порядок — часть протокола, а не вежливость.
        assertEquals("""{"token":"жетон"}""", protocol.authFrame("жетон"))
        assertFailsWith<IllegalArgumentException> { protocol.authFrame("") }
    }

    @Test
    fun курсор_null_означает_серверную_копию() {
        // Так и надо на первом подключении: своя копия может быть старше, и тогда часть
        // событий приедет дважды. Дубли безвредны, но платить трафиком незачем.
        assertEquals("""{"event":"sync.pull","cursor":null,"limit":100}""", protocol.pullFrame(null))
        assertEquals("""{"event":"sync.pull","cursor":42,"limit":10}""", protocol.pullFrame(42, 10))
    }

    @Test
    fun предел_страницы_проверяется_у_нас_а_не_на_сервере() {
        // Сервер молча урежет всё, что вне 1..500, до 100 — и это выглядело бы как
        // «сервер теряет события».
        assertFailsWith<IllegalArgumentException> { protocol.pullFrame(null, 0) }
        assertFailsWith<IllegalArgumentException> {
            protocol.pullFrame(null, EventStreamProtocol.MAX_LIMIT + 1)
        }
    }

    @Test
    fun подтверждение_только_положительного_события() {
        assertEquals("""{"event":"ack","event_id":7}""", protocol.ackFrame(7))
        // Ноль у сервера означает «нет идентификатора», и он такой ack игнорирует:
        // клиент решил бы, что курсор двинулся, а он стоит.
        assertFailsWith<IllegalArgumentException> { protocol.ackFrame(0) }
        assertFailsWith<IllegalArgumentException> { protocol.ackFrame(-1) }
    }

    // ── кадры сервера ────────────────────────────────────────────────────────

    @Test
    fun подтверждение_токена_даёт_готовность() {
        val решение = protocol.decide("""{"event":"ok","device_id":"d-1"}""")
        assertEquals(EventStreamProtocol.Decision.Ready("d-1"), решение)
    }

    @Test
    fun сообщение_разбирается_целиком() {
        val решение = protocol.decide(кадрСообщения())

        assertIs<EventStreamProtocol.Decision.Deliver>(решение)
        assertEquals(5, решение.event.eventId)
        assertEquals("chat-1", решение.event.chatId)
        assertEquals(77, решение.event.messageId)
        assertContentEquals(конверт, решение.event.envelope)
    }

    @Test
    fun неполное_сообщение_пропускается_но_курсор_двигает() {
        // Записывать нечего, а курсор двигать надо: иначе он застрянет на испорченном
        // событии навсегда, и канал встанет целиком.
        val решение = protocol.decide("""{"event":"message.new","event_id":9,"chat_id":"c"}""")

        assertIs<EventStreamProtocol.Decision.Skip>(решение)
        assertEquals(9, решение.eventId, "курсор обязан двинуться дальше испорченного")
    }

    @Test
    fun конверт_не_base64url_это_пропуск_а_не_падение() {
        val решение = protocol.decide(
            """{"event":"message.new","event_id":9,"chat_id":"c","message_id":1,"envelope":"!!!"}""",
        )
        assertIs<EventStreamProtocol.Decision.Skip>(решение)
    }

    @Test
    fun догон_сообщает_остаток() {
        // more = true означает «есть ещё», и следующую страницу обязан попросить клиент.
        // Не попросит — остаток истории не приедет, а канал будет выглядеть исправным.
        val решение = protocol.decide(
            """{"event":"sync.done","count":100,"next_cursor":250,"more":true}""",
        )
        assertEquals(EventStreamProtocol.Decision.SyncDone(100, 250, true), решение)

        val последняя = protocol.decide("""{"event":"sync.done","count":7,"next_cursor":257,"more":false}""")
        assertEquals(EventStreamProtocol.Decision.SyncDone(7, 257, false), последняя)
    }

    @Test
    fun промежуток_требует_догона_историей_а_не_продолжения() {
        // Главное правило приёма. sync.gap означает, что события до next_cursor сервер
        // удалил по сроку хранения: живой канал их не принесёт никогда. Молча продолжить
        // с этого места — значит навсегда потерять переписку за промежуток.
        val решение = protocol.decide("""{"event":"sync.gap","next_cursor":900}""")

        assertIs<EventStreamProtocol.Decision.NeedHistory>(решение)
        assertEquals(900, решение.fromCursor)
    }

    @Test
    fun беда_сервера_отличается_от_испорченного_кадра() {
        // Первое — повторить позже, второе — пропустить. Одно решение на два случая
        // означало бы либо вечный повтор мусора, либо потерю живого канала.
        val беда = protocol.decide("""{"event":"error","code":"internal"}""")
        assertEquals(EventStreamProtocol.Decision.ServerTrouble("internal"), беда)

        assertIs<EventStreamProtocol.Decision.Skip>(protocol.decide("не json"))
    }

    @Test
    fun незнакомый_кадр_двигает_курсор() {
        // typing, receipt, presence — сервер обещает их следующими итерациями. Не
        // подтверждать непонятное «чтобы не потерять» значило бы остановить канал
        // целиком из-за кадра, который мы всё равно не умеем прочитать.
        val решение = protocol.decide("""{"event":"typing","event_id":31,"chat_id":"c"}""")

        assertIs<EventStreamProtocol.Decision.Skip>(решение)
        assertEquals(31, решение.eventId)
        assertTrue(решение.reason.contains("typing"), "причина обязана называть кадр: ${решение.reason}")
    }

    @Test
    fun мусор_не_роняет_канал() {
        for (мусор in listOf("", "{}", "[]", "не json", """{"event":123}""")) {
            val решение = protocol.decide(мусор)
            assertIs<EventStreamProtocol.Decision.Skip>(решение, "вход «$мусор» обязан быть пропуском")
        }
    }
}
