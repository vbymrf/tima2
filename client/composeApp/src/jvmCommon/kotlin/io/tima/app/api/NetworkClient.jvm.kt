package io.tima.app.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.time.Duration

/**
 * Сроки задаются на самом движке, а не плагином HttpTimeout: тот сознательно
 * пропускает запросы обновления до WebSocket, и рукопожатие WS осталось бы на
 * умолчании в 10 секунд — ровно там, где и ломалось.
 */
actual fun createHttpClient(json: Json): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(json) }
    install(WebSockets)
    engine {
        config {
            connectTimeout(Duration.ofMillis(NetworkTuning.CONNECT_MS))
            readTimeout(Duration.ofMillis(NetworkTuning.SOCKET_MS))
            writeTimeout(Duration.ofMillis(NetworkTuning.SOCKET_MS))
            // Общего срока на вызов нет намеренно: большое вложение по мобильной
            // сети идёт минутами и это нормальная работа. Мёртвым соединение
            // делает тишина, её и ловим read/writeTimeout.
            callTimeout(Duration.ZERO)
            pingInterval(Duration.ofMillis(NetworkTuning.WS_PING_MS))
            retryOnConnectionFailure(true)
        }
    }
}
