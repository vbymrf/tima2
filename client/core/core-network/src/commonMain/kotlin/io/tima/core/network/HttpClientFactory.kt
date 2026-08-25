package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Настройки клиента, которые не зависят от платформы — К3.2.
 *
 * Числа взяты из состояния связи, снятого в живой мобильной сети v1 ([LinkState]), а
 * не из значений по умолчанию Ktor: у него таймаут запроса бесконечный, и в метро
 * отправка висит, пока человек не убьёт приложение.
 */
data class TransportTuning(
    /**
     * Полный срок запроса. Пятнадцать секунд — компромисс: конверт до 4 МиБ по краю
     * сети успевает уйти, а зависшее соединение не держит место в насосе.
     */
    val requestTimeoutMs: Long = 15_000,
    /** Установление соединения. Дольше десяти секунд — это уже нет сети. */
    val connectTimeoutMs: Long = 10_000,
    /** Тишина в открытом соединении: мобильная сеть роняет их молча. */
    val socketTimeoutMs: Long = 15_000,
)

/**
 * Общая настройка клиента: **поведение живёт здесь, а не в платформенных файлах**.
 *
 * Так сделано намеренно. Платформенное — только выбор движка ([httpEngine]); всё,
 * что можно решить неправильно, решается один раз в общем коде. Иначе повторяется
 * история v1, где Android и Desktop разошлись в мелочах, и разошлись молча.
 *
 * Два решения здесь важнее остальных:
 *
 * **Перенаправления не выполняются.** `POST /api/v1/messages` едет с заголовком
 * `Authorization: Bearer <токен устройства>`. Пойдя за `302`, клиент отдал бы токен
 * тому хосту, который назвал ответ, — то есть посреднику, портальному шлюзу или
 * подменённому DNS. Настоящий сервер перенаправлений на эту ручку не отдаёт, значит
 * `302` здесь всегда чужой.
 *
 * **Ошибочный статус не превращается в исключение** (`expectSuccess = false`). Разбор
 * ответа — дело [HttpMessageTransport], и он различает окончательный отказ от
 * временного. Включи `expectSuccess`, и `403` пришёл бы исключением, а транспорт по
 * своему же правилу «исключение → [SendOutcome.Retry]» повторял бы вечно конверт,
 * который сервер уже отверг по сути.
 */
fun HttpClientConfig<*>.timaDefaults(tuning: TransportTuning = TransportTuning()) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = tuning.requestTimeoutMs
        connectTimeoutMillis = tuning.connectTimeoutMs
        socketTimeoutMillis = tuning.socketTimeoutMs
    }
}

/** Клиент для боевого хода: движок по платформе, настройки общие. */
fun timaHttpClient(tuning: TransportTuning = TransportTuning()): HttpClient =
    HttpClient(httpEngine()) { timaDefaults(tuning) }

/**
 * Тот же клиент, но с живым каналом.
 *
 * **Websockets ставится сразу, и клиент остаётся один.** Второй клиент означал бы
 * второй набор настроек — таймауты, повторы, движок, — и однажды они разошлись бы:
 * запрос ходил бы по одним правилам, живой канал по другим, а выглядело бы это как
 * «сообщения приходят, а уведомления нет».
 *
 * Живёт здесь, а не в композиции приложения: `HttpClient` не должен подниматься выше
 * этого модуля, иначе смена движка или политики токенов задевает всех потребителей.
 */
fun timaHttpClientСКаналом(tuning: TransportTuning = TransportTuning()): HttpClient =
    HttpClient(httpEngine()) {
        timaDefaults(tuning)
        install(WebSockets)
    }

/**
 * Движок по платформе — единственное, что здесь платформенное.
 *
 * OkHttp на JVM (Desktop и Android — один и тот же движок, чтобы поведение сети не
 * расходилось между ними), Darwin на Apple: он ходит через системный стек, а значит
 * уважает настройки VPN и доверие к сертификатам, заданные на устройстве.
 */
expect fun httpEngine(): HttpClientEngineFactory<*>

/**
 * Соединение с сервером: адрес и клиент, собранные вместе.
 *
 * Заведено, чтобы **Ktor не поднимался выше этого модуля**. Композиция приложения
 * берёт готовое соединение и передаёт его дальше, ни разу не называя ни `HttpClient`,
 * ни движок: смена того и другого остаётся правкой одного модуля.
 */
class ServerLink(val route: ServerRoute, val client: HttpClient) {
    companion object {
        /**
         * @param живойКанал ставить ли WebSockets. Клиент при этом остаётся ОДИН:
         *   второй означал бы второй набор настроек, и они разошлись бы.
         */
        fun открыть(host: String, живойКанал: Boolean = false): ServerLink = ServerLink(
            route = ServerRoute.from(RouteConfig(host = host)),
            client = if (живойКанал) timaHttpClientСКаналом() else timaHttpClient(),
        )
    }
}
