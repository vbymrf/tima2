package io.tima.app

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.tima.app.api.createHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Медленный ответ не должен обрываться клиентом.
 *
 * Это регрессионный тест на конкретную поломку: клиент работал на умолчаниях OkHttp —
 * 10 секунд на чтение — и в мобильной сети переставал работать целиком. В журнале
 * это выглядело как ровная череда «Read timed out» с шагом ровно десять секунд.
 *
 * Тест намеренно идёт дольше десяти секунд: короче его сделать нельзя, не перестав
 * проверять именно то, что сломалось.
 */
class NetworkTimeoutTest {

    @Test
    fun `ответ дольше прежнего срока ожидания доходит целиком`() = runBlocking {
        val slowMs = 12_000L
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/slow") { exchange ->
            Thread.sleep(slowMs)
            val body = "ок".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val client = createHttpClient(Json { ignoreUnknownKeys = true })
        try {
            val port = server.address.port
            assertEquals("ок", client.get("http://127.0.0.1:$port/slow").bodyAsText())
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
