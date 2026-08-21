package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.tima.core.outbox.SendOutcome
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Общая настройка клиента (К3.2). Проверяется на `MockEngine`: настройка тем и ценна,
 * что от движка не зависит, — значит и проверять её надо без движка.
 *
 * Название движка по платформе проверяется отдельно ([ожидаемыйДвижок]): собрать
 * `iosMain` с движком OkHttp нельзя, а вот получить в клиенте не тот движок, который
 * задумывали, — можно.
 */
class HttpClientFactoryTest {

    private val route = ServerRoute.from(RouteConfig(host = "example.com"))

    private fun транспорт(отвечает: io.ktor.client.engine.mock.MockRequestHandler): Pair<HttpMessageTransport, MockEngine> {
        val engine = MockEngine(отвечает)
        val client = HttpClient(engine) { timaDefaults() }
        return HttpMessageTransport(route, client, token = { "токен-устройства" }) to engine
    }

    @Test
    fun перенаправление_не_выполняется_и_токен_не_уходит_чужому_хосту() = runTest {
        // POST едет с Bearer-токеном устройства. Пойдя за 302, клиент отдал бы токен
        // тому хосту, который назвал ответ: портальному шлюзу, посреднику,
        // подменённому DNS.
        val (transport, engine) = транспорт {
            respond(
                "", HttpStatusCode.Found,
                headersOf("Location", "https://чужой.example/api/v1/messages"),
            )
        }

        val исход = transport.send("d", byteArrayOf(1))

        assertEquals(1, engine.requestHistory.size, "второго запроса быть не должно")
        assertTrue(
            engine.requestHistory.none { it.url.host.contains("чужой") },
            "токен ушёл хосту, которого мы не выбирали: ${engine.requestHistory.map { it.url }}",
        )
        // 302 от этой ручки не бывает — значит отвечает не наш сервер. Конверт при этом
        // не виноват, поэтому повтор, а не отказ.
        assertIs<SendOutcome.Retry>(исход)
    }

    @Test
    fun ошибочный_статус_не_становится_исключением() = runTest {
        // Включённый expectSuccess обратил бы 403 в исключение, а транспорт по своему
        // правилу «исключение → повтор» повторял бы вечно конверт, отвергнутый по сути.
        val (transport, _) = транспорт {
            respond(
                """{"code":"bad_signature"}""", HttpStatusCode.Forbidden,
                headersOf("Content-Type", "application/json"),
            )
        }
        assertIs<SendOutcome.Permanent>(transport.send("d", byteArrayOf(1)))
    }

    @Test
    fun сроки_заданы_явно_а_не_оставлены_бесконечными() {
        // У Ktor срок запроса по умолчанию бесконечный: в метро отправка висела бы,
        // пока человек не убьёт приложение.
        val client = HttpClient(MockEngine { respond("") }) { timaDefaults() }
        assertNotNull(client.pluginOrNull(HttpTimeout), "плагин сроков обязан стоять")
        client.close()
    }

    @Test
    fun движок_тот_который_задуман_для_платформы() {
        val client = timaHttpClient()
        val имя = client.engine::class.simpleName ?: ""
        assertTrue(
            имя.contains(ожидаемыйДвижок, ignoreCase = true),
            "на этой платформе ожидался движок $ожидаемыйДвижок, а собрался $имя",
        )
        client.close()
    }
}

/** Какой движок обязан оказаться в клиенте на этой платформе. */
expect val ожидаемыйДвижок: String
