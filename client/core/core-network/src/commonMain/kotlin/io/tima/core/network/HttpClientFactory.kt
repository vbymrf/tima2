package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout

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
 * Движок по платформе — единственное, что здесь платформенное.
 *
 * OkHttp на JVM (Desktop и Android — один и тот же движок, чтобы поведение сети не
 * расходилось между ними), Darwin на Apple: он ходит через системный стек, а значит
 * уважает настройки VPN и доверие к сертификатам, заданные на устройстве.
 */
expect fun httpEngine(): HttpClientEngineFactory<*>
