package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.util.AttributeKey
import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import kotlinx.datetime.Clock
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
fun HttpClientConfig<*>.timaDefaults(
    tuning: TransportTuning = TransportTuning(),
    /** Чем обновлять токен при `401`. `null` — не обновлять вовсе (проверки, вход). */
    renewal: TokenRenewal? = null,
) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = tuning.requestTimeoutMs
        connectTimeoutMillis = tuning.connectTimeoutMs
        socketTimeoutMillis = tuning.socketTimeoutMs
    }
    // ── Просроченный токен обновляется сам (находка 2026-09-06) ────────────────
    //
    // Токен живёт сутки, и до этой правки истёкший означал `401` на каждой ручке под
    // токеном — молча, без единого слова человеку. Здесь единственное место, где это
    // лечится один раз для всех полутора десятков Api: ответ `401` → обновить → повторить.
    //
    // **Повтор ровно один.** Если и он вернул `401`, дело не в сроке: устройство отозвано
    // или ключ не тот, и второй круг ничего не изменит, зато превратит отказ в петлю.
    if (renewal != null) {
        install(createClientPlugin("ОбновлениеТокена") {
            on(Send) { request ->
                val first = proceed(request)
                if (first.response.status != HttpStatusCode.Unauthorized) return@on first
                val path = shortPath(request.url.build().encodedPath)
                // Общий код: сервер отказал по авторизации. Причину назовёт частный —
                // её знает тот, кто владеет токеном, а не транспорт.
                Journal.trouble(LogCode.NET_401, "сервер не принял токен — пробую обновить", "путь" to path)
                val fresh = renewal.renew?.invoke()
                if (fresh == null) {
                    Journal.trouble(
                        LogCode.AUTH_RENEW_FAILED,
                        "обновить не вышло: ручки под токеном будут отказывать",
                        "путь" to path,
                    )
                    return@on first
                }
                Journal.note(LogCode.AUTH_RENEWED, "повторяю запрос", "путь" to path)
                request.headers.remove(HttpHeaders.Authorization)
                request.headers.append(HttpHeaders.Authorization, "Bearer " + fresh)
                val second = proceed(request)
                if (second.response.status == HttpStatusCode.Unauthorized) {
                    // Второй отказ означает не срок: устройство отозвано либо ключ не тот.
                    // Сказать это здесь дешевле, чем оставить читающему два одинаковых 401.
                    Journal.trouble(
                        LogCode.AUTH_REVOKED,
                        "и с новым токеном отказ: дело не в сроке — устройство отозвано или ключ не тот",
                        "путь" to path,
                    )
                }
                second
            }
        })
    }

    // ── Каждый вызов попадает в журнал (ПЛАН-ОТЛАДКИ.md, Б1) ───────────────────
    //
    // Здесь, а не в каждом Api по отдельности: сетевых классов у нас полтора десятка, и
    // «забыли записать в новом» — вопрос времени. Пишется путь, код ответа и время;
    // **тело не пишется никогда** — в нём переписка.
    //
    // Путь при этом без строки запроса: в ней бывают коды и идентификаторы сессий.
    install(createClientPlugin("ЖурналВызовов") {
        onRequest { request, _ ->
            request.attributes.put(startedAt, Clock.System.now().toEpochMilliseconds())
        }
        onResponse { response ->
            val started = response.call.request.attributes.getOrNull(startedAt)
            val spent = started?.let { Clock.System.now().toEpochMilliseconds() - it } ?: -1
            val path = shortPath(response.call.request.url.encodedPath)
            val method = response.call.request.method.value
            val code = when {
                response.status == HttpStatusCode.Unauthorized -> LogCode.NET_401
                response.status.value >= 400 -> LogCode.NET_ERROR
                else -> LogCode.NET_CALL
            }
            val details = arrayOf<Pair<String, Any?>>(
                "путь" to (method + " " + path),
                "код" to response.status.value,
                "мс" to (if (spent >= 0) spent else null),
            )
            if (response.status.value >= 400) Journal.trouble(code, details = details)
            else Journal.note(code, details = details)
        }
    })
}

/** Когда ушёл запрос — чтобы в журнале было время ответа, а не только его код. */
private val startedAt = AttributeKey<Long>("tima-started-at")

/**
 * Кто умеет обновить токен доступа, когда сервер ответил `401`.
 *
 * **Ссылка, заполняемая позже, и это не небрежность.** Клиент создаётся раньше, чем
 * известно устройство: адрес сервера нужен и на экране входа, где сессии ещё нет. Держать
 * ради этого два клиента с разными настройками — верный способ их разойтись.
 *
 * `null` в [renew] означает «обновлять нечем»: человек не вошёл, ключа устройства нет.
 * Тогда `401` остаётся `401` и разбирается вызывающим, как разбирался всегда.
 */
class TokenRenewal {
    /** Возвращает новый токен или `null`, если обновить не удалось. */
    var renew: (suspend () -> String?)? = null
}

/**
 * Путь для журнала: длинные сегменты заменяются на `{id}`.
 *
 * **Поймано первым же настоящим отчётом (ПЛАН-ОТЛАДКИ.md §6, 2026-09-06.)** Журнал
 * проходил общую чистку `scrub`, и она честно вырезала идентификатор в пути:
 *
 *     PUT <вырезано 27> → 401
 *
 * Строка бесполезна — непонятно, какая ручка отказала. Идентификатор в пути не секрет:
 * он и так виден серверу и не открывает ничего сам по себе. А вот **какая ручка вернула
 * 401** — это и есть половина разбора.
 */
internal fun shortPath(path: String): String =
    path.split('/').joinToString("/") { segment ->
        if (segment.length > LONG_SEGMENT && segment.any { it.isDigit() }) "{id}" else segment
    }

/**
 * Порог «это идентификатор, а не имя ручки».
 *
 * Шестнадцать знаков: наши ручки называются словами (`messages`, `problem-reports`), а
 * идентификаторы — это UUID и base64url, они длиннее и содержат цифры. Проверка на цифру
 * нужна, чтобы не превратить в `{id}` длинное человеческое имя.
 */
private const val LONG_SEGMENT = 16

/** Клиент для боевого хода: движок по платформе, настройки общие. */
fun timaHttpClient(
    tuning: TransportTuning = TransportTuning(),
    renewal: TokenRenewal? = null,
): HttpClient = HttpClient(httpEngine()) { timaDefaults(tuning, renewal) }

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
fun timaHttpClientWithChannel(
    tuning: TransportTuning = TransportTuning(),
    renewal: TokenRenewal? = null,
): HttpClient = HttpClient(httpEngine()) {
    timaDefaults(tuning, renewal)
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
        fun open(
            host: String,
            liveChannel: Boolean = false,
            /** Чем обновлять токен при `401`; `null` — не обновлять (вход, проверки). */
            renewal: TokenRenewal? = null,
        ): ServerLink = ServerLink(
            route = ServerRoute.from(RouteConfig(host = host)),
            client = if (liveChannel) timaHttpClientWithChannel(renewal = renewal)
            else timaHttpClient(renewal = renewal),
        )
    }
}
