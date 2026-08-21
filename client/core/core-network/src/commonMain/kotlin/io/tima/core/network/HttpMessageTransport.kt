package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.core.outbox.SendOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Отправка конверта на сервер: `POST /api/v1/messages`.
 *
 * **Контракт взят из кода сервера, а не из документации** — сверка Д3 показала, что
 * каталог API описывал 26 маршрутов из 71, и этого среди описанных не было. Значит
 * единственный надёжный источник здесь `internal/api/server.go`.
 *
 * ```
 * тело:      protobuf Envelope, не более 4 MiB
 * заголовок: X-Client-Msg-Id — обязателен, иначе 400 no_client_msg_id
 * 201:       {"message_id": N}
 * 200:       {"duplicate": true, "message_id": N}   ← повтор уже дошёл
 * ```
 *
 * **`message_id` в ответе — не серверный идентификатор.** Сервер возвращает тот,
 * который назначил отправитель и который входит в подпись (`meta.message_id`).
 * Отдельной нумерации у сервера для личных сообщений нет.
 */
class HttpMessageTransport(
    private val route: ServerRoute,
    private val client: HttpClient,
    /** Токен устройства; берётся при каждом вызове — он живёт меньше приложения. */
    private val token: () -> String,
) {

    /**
     * Одна попытка отправки. Исключений наружу не бросает: любая беда становится
     * [SendOutcome], потому что решение «повторять или нет» принимает очередь, а не
     * транспорт.
     */
    suspend fun send(dedupKey: String, envelope: ByteArray): SendOutcome {
        if (envelope.size > MAX_ENVELOPE_BYTES) {
            // Проверяем до отправки: 4 МиБ по проводу ради гарантированного 413 —
            // это трафик человека, потраченный впустую.
            return SendOutcome.Permanent(
                "конверт ${envelope.size} байт, предел $MAX_ENVELOPE_BYTES",
            )
        }
        val response = try {
            client.post(route.api("/api/v1/messages")) {
                header("Authorization", "Bearer ${token()}")
                header("X-Client-Msg-Id", dedupKey)
                contentType(ContentType.Application.ProtoBuf)
                setBody(envelope)
            }
        } catch (e: Throwable) {
            // Сеть, TLS, разорванное соединение. Пауза берётся из состояния связи,
            // распознанного по признакам из живых испытаний v1 — а не из общего
            // «подождём пять секунд».
            return SendOutcome.Retry(afterMs = classifyFailure(e).retryDelayMs)
        }
        return outcomeOf(response)
    }

    private suspend fun outcomeOf(response: HttpResponse): SendOutcome {
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrNull()
        val messageId = body?.longField("message_id")
        val code = body?.stringField("code")

        return when (response.status) {
            HttpStatusCode.Created -> messageId
                ?.let { SendOutcome.Accepted(it) }
                // 201 без идентификатора — либо не наш сервер, либо сломанный ответ.
                // Повторять бессмысленно: конверт-то приняли.
                ?: SendOutcome.Permanent("201 без message_id")

            HttpStatusCode.OK -> when {
                body?.boolField("duplicate") == true && messageId != null ->
                    SendOutcome.Duplicate(messageId)
                // 200 у этой ручки бывает только на повторе. Всё остальное —
                // неизвестный ответ, и молча считать его успехом нельзя.
                else -> SendOutcome.Permanent("200 без признака duplicate")
            }

            // Конверт негоден по сути: подпись, разбор, размер, чужой отправитель.
            // Повтор ничего не изменит, и держать такое в очереди вечно — значит
            // никогда не дойти до следующего сообщения.
            HttpStatusCode.BadRequest,
            HttpStatusCode.Forbidden,
            HttpStatusCode.PayloadTooLarge,
            -> SendOutcome.Permanent("${response.status.value} ${code ?: "без кода"}")

            HttpStatusCode.Unauthorized -> when (code) {
                // Устройство отозвано — это не «попробуем позже», это конец пути для
                // этого устройства. Повторять до бесконечности значит скрывать от
                // человека, что ему надо войти заново.
                "device_revoked" -> SendOutcome.Permanent("устройство отозвано")
                // Токен истёк: обновление сессии — дело слоя авторизации, а очередь
                // просто подождёт. Отдельного исхода «нужен новый токен» у неё нет.
                else -> SendOutcome.Retry(afterMs = LinkState.ONLINE.retryDelayMs)
            }

            HttpStatusCode.TooManyRequests ->
                SendOutcome.Retry(afterMs = retryAfterMs(response) ?: LinkState.BLOCKED.retryDelayMs)

            else -> when {
                // 5xx и всё неизвестное — временно. Неизвестный код от неизвестного
                // посредника (прокси, портал в кафе) не повод выбрасывать сообщение.
                response.status.value >= 500 ->
                    SendOutcome.Retry(afterMs = retryAfterMs(response) ?: LinkState.ONLINE.retryDelayMs)
                else -> SendOutcome.Retry(afterMs = LinkState.ONLINE.retryDelayMs)
            }
        }
    }

    /** `Retry-After` в секундах; заголовок сервер может и не прислать. */
    private fun retryAfterMs(response: HttpResponse): Long? =
        response.headers["Retry-After"]?.toLongOrNull()?.times(1_000)

    private fun JsonObject.longField(name: String): Long? =
        this[name]?.jsonPrimitive?.longOrNull

    private fun JsonObject.boolField(name: String): Boolean? =
        this[name]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.stringField(name: String): String? =
        runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()

    companion object {
        /**
         * Предел из `internal/api/server.go`: `maxEnvelopeBytes = 4 << 20`. Медиа
         * ходят через объектное хранилище, а не этой ручкой.
         */
        const val MAX_ENVELOPE_BYTES: Int = 4 shl 20
    }
}
