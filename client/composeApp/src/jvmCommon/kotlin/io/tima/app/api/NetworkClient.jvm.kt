package io.tima.app.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.serialization.kotlinx.json.json
import io.tima.app.diag.AppDiagnostics
import kotlinx.serialization.json.Json
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Разбор имени с памятью на прошлый удачный ответ.
 *
 * В журнале телефона «Unable to resolve host … No address associated with hostname»
 * шло пачками по полторы минуты сразу после смены сети. Адрес сервера при этом не
 * менялся — менялся только резолвер под ногами. Держать из-за этого приложение
 * оторванным незачем: адрес, который работал минуту назад, почти наверняка работает
 * и сейчас.
 *
 * Это именно ЗАПАСНОЙ путь, а не подмена системного разбора: пока резолвер отвечает,
 * слушаем только его, и переезд сервера подхватится обычным порядком. Память живёт в
 * пределах запуска — она закрывает смену сети на ходу, а не холодный старт без DNS.
 */
private object RememberingDns : Dns {
    private val lastGood = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> = try {
        Dns.SYSTEM.lookup(hostname).also { if (it.isNotEmpty()) lastGood[hostname] = it }
    } catch (e: UnknownHostException) {
        val remembered = lastGood[hostname] ?: throw e
        AppDiagnostics.add("сеть: имя $hostname не разобралось, беру прошлый адрес ${remembered.first().hostAddress}")
        remembered
    }
}

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
            dns(RememberingDns)
        }
    }
}
