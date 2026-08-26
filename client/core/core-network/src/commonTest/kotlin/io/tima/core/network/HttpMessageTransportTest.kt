package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.errors.IOException
import io.tima.core.outbox.SendOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Транспорт проверяется без сети и без сервера — `MockEngine` отвечает тем, что
 * отвечает настоящий сервер.
 *
 * **Ожидаемые ответы взяты из кода сервера** (`internal/api/server.go`), а не из
 * каталога API: сверка Д3 показала, что этой ручки в каталоге нет вовсе. Поэтому
 * здесь дословно: 201 с `message_id`, 200 с `duplicate`, 403 на подпись, 413 на
 * размер, 401 с кодом `device_revoked`.
 */
class HttpMessageTransportTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))
    private val envelope = byteArrayOf(1, 2, 3)

    /** Транспорт с заданным ответом движка. */
    private fun server(
        responds: io.ktor.client.engine.mock.MockRequestHandler,
    ): Pair<HttpMessageTransport, MockEngine> {
        val engine = MockEngine(responds)
        val transport = HttpMessageTransport(
            route = route,
            client = HttpClient(engine),
            token = { "токен-устройства" },
        )
        return transport to engine
    }

    // ── форма запроса ────────────────────────────────────────────────────────

    @Test
    fun запрос_идёт_по_нужному_адресу_с_обязательными_заголовками() = runTest {
        val (transport, engine) = server { respond(
            content = """{"message_id":42}""",
            status = HttpStatusCode.Created,
            headers = headersOf("Content-Type", "application/json"),
        ) }

        transport.send("dedup-1", envelope)

        val request = engine.requestHistory.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://api.example.com/api/v1/messages", request.url.toString())
        // Без этого заголовка сервер отвечает 400 no_client_msg_id, и повтор после
        // обрыва даёт дубль у собеседника.
        assertEquals("dedup-1", request.headers["X-Client-Msg-Id"])
        assertEquals("Bearer токен-устройства", request.headers["Authorization"])
        assertTrue(
            request.body.contentType?.toString()?.contains("protobuf") == true,
            "конверт едет protobuf, а не JSON: получено ${request.body.contentType}",
        )
    }

    @Test
    fun слишком_большой_конверт_не_отправляется_вовсе() = runTest {
        // 4 МиБ по проводу ради гарантированного 413 — это трафик человека,
        // потраченный впустую.
        val (transport, engine) = server { respond("", HttpStatusCode.Created) }
        val big = ByteArray(HttpMessageTransport.MAX_ENVELOPE_BYTES + 1)

        val outcome = transport.send("dedup-1", big)

        assertIs<SendOutcome.Permanent>(outcome)
        assertEquals(0, engine.requestHistory.size, "запрос не должен был уйти")
    }

    // ── ответы сервера ───────────────────────────────────────────────────────

    @Test
    fun создано_становится_принятым() = runTest {
        val (transport, _) = server { respond(
            """{"message_id":42}""", HttpStatusCode.Created,
            headersOf("Content-Type", "application/json"),
        ) }
        assertEquals(SendOutcome.Accepted(42), transport.send("d", envelope))
    }

    @Test
    fun признак_duplicate_становится_подтверждением_а_не_ошибкой() = runTest {
        // Сервер дедуплицирует по client_msg_id и отвечает 200 с duplicate. Считать
        // это ошибкой значит повторять вечно сообщение, которое давно дошло.
        val (transport, _) = server { respond(
            """{"duplicate":true,"message_id":7}""", HttpStatusCode.OK,
            headersOf("Content-Type", "application/json"),
        ) }
        assertEquals(SendOutcome.Duplicate(7), transport.send("d", envelope))
    }

    @Test
    fun двести_без_признака_duplicate_не_считается_успехом() = runTest {
        // 200 у этой ручки бывает только на повторе. Молча считать неизвестный ответ
        // успехом — значит потерять сообщение, решив, что оно дошло.
        val (transport, _) = server { respond(
            """{}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"),
        ) }
        assertIs<SendOutcome.Permanent>(transport.send("d", envelope))
    }

    @Test
    fun создано_без_идентификатора_не_считается_успехом() = runTest {
        val (transport, _) = server { respond(
            """{}""", HttpStatusCode.Created, headersOf("Content-Type", "application/json"),
        ) }
        assertIs<SendOutcome.Permanent>(transport.send("d", envelope))
    }

    @Test
    fun отказ_по_подписи_и_размеру_не_повторяется() = runTest {
        for (status in listOf(
            HttpStatusCode.BadRequest,
            HttpStatusCode.Forbidden,
            HttpStatusCode.PayloadTooLarge,
        )) {
            val (transport, _) = server { respond(
                """{"code":"bad_signature","message":"подпись не прошла"}""", status,
                headersOf("Content-Type", "application/json"),
            ) }
            assertIs<SendOutcome.Permanent>(
                transport.send("d", envelope),
                "статус $status обязан быть окончательным отказом",
            )
        }
    }

    @Test
    fun отозванное_устройство_это_конец_пути_а_не_ожидание() = runTest {
        // Повторять до бесконечности значит скрывать от человека, что ему надо войти
        // заново.
        val (transport, _) = server { respond(
            """{"code":"device_revoked","message":"устройство отозвано"}""",
            HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"),
        ) }
        assertIs<SendOutcome.Permanent>(transport.send("d", envelope))
    }

    @Test
    fun истёкший_токен_это_ожидание_а_не_отказ() = runTest {
        // Обновление сессии — дело слоя авторизации; очередь просто подождёт.
        val (transport, _) = server { respond(
            """{"code":"unauthorized","message":"нужна авторизация"}""",
            HttpStatusCode.Unauthorized, headersOf("Content-Type", "application/json"),
        ) }
        assertIs<SendOutcome.Retry>(transport.send("d", envelope))
    }

    @Test
    fun пятисотые_повторяются() = runTest {
        val (transport, _) = server { respondError(HttpStatusCode.InternalServerError) }
        assertIs<SendOutcome.Retry>(transport.send("d", envelope))
    }

    @Test
    fun подсказка_retry_after_доезжает_до_очереди() = runTest {
        val (transport, _) = server { respond(
            "", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "30"),
        ) }
        val outcome = transport.send("d", envelope)
        assertIs<SendOutcome.Retry>(outcome)
        assertEquals(30_000, outcome.afterMs, "секунды из заголовка обязаны стать миллисекундами")
    }

    @Test
    fun неизвестный_код_от_посредника_не_выбрасывает_сообщение() = runTest {
        // Портал в кафе, прокси, шлюз оператора — источник неизвестных ответов. Это
        // не повод считать конверт негодным.
        val (transport, _) = server { respond("<html>captive portal</html>", HttpStatusCode(418, "Teapot")) }
        assertIs<SendOutcome.Retry>(transport.send("d", envelope))
    }

    // ── сеть ─────────────────────────────────────────────────────────────────

    @Test
    fun обрыв_сети_становится_повтором_с_паузой_из_состояния_связи() = runTest {
        // Пауза берётся не из общего «подождём пять секунд», а из признаков, снятых в
        // живой мобильной сети (LinkState, перенесён из v1).
        val (transport, _) = server { throw IOException("Unable to resolve host") }
        val outcome = transport.send("d", envelope)
        assertIs<SendOutcome.Retry>(outcome)
        assertEquals(LinkState.NO_NETWORK.retryDelayMs, outcome.afterMs)
    }

    @Test
    fun исключений_наружу_не_бросает_ни_при_какой_беде() = runTest {
        // Решение «повторять или нет» принимает очередь. Транспорт, бросающий
        // исключение, заставил бы каждого вызывающего заводить свою политику.
        val (transport, _) = server { throw IllegalStateException("что угодно") }
        assertIs<SendOutcome.Retry>(transport.send("d", envelope))
    }
}
