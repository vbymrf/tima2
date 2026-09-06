package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Отправка отчёта о проблеме (ПЛАН-ОТЛАДКИ.md, Б3 и Б4).
 *
 * **Токен необязателен, и это решение заказчика 2026-09-06.** «Не могу войти» — самая
 * частая жалоба, и именно её мы потеряли бы, требуя авторизации. Есть токен — сервер сам
 * достанет из него `user_id` и `device_id`; нет токена — отчёт принимается как
 * неопознанный, с пределом частоты на стороне сервера.
 *
 * **Идентификаторы аккаунта клиент не шлёт вовсе.** Человеку показывает — да, чтобы он
 * видел, что уходит; отправляет — нет. Присланному `user_id` верить нельзя: его можно
 * написать любой, и тогда чужой отчёт ляжет на чужой аккаунт.
 */
class ProblemsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    /** Токен устройства или `null`, если человек не вошёл. */
    private val token: () -> String?,
) {

    suspend fun send(report: ProblemPost): ProblemSendResult {
        val response = try {
            client.post(route.api("/api/v1/problem-reports")) {
                contentType(ContentType.Application.Json)
                token()?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
                setBody(
                    buildJsonObject {
                        put("kind", JsonPrimitive(report.kind))
                        put("text", JsonPrimitive(report.text))
                        put("origin", JsonPrimitive(report.origin))
                        put("platform", JsonPrimitive(report.platform))
                        put("model", JsonPrimitive(report.model))
                        put("os", JsonPrimitive(report.os))
                        put("build", JsonPrimitive(report.build))
                        put("stream", JsonPrimitive(report.stream))
                        put("nickname", JsonPrimitive(report.nickname))
                        // Журнал — единственное поле, где встречается всё подряд: кавычки,
                        // переводы строк, управляющие знаки из сообщений об ошибках.
                        // Поэтому он идёт через сериализатор, а не через склейку строк.
                        put("log", JsonPrimitive(report.log))
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return ProblemSendResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK && response.status != HttpStatusCode.Created) {
            return ProblemSendResult.Refused(response.status.value, body.codeOf())
        }
        // Номер обращения приходит от сервера: свой придуманный не найдёт ничего в нашей
        // базе, а человек называет его в разговоре.
        return ProblemSendResult.Sent(body?.str("number").orEmpty())
    }
}

/**
 * Что уходит на сервер. Ровно это человек видит по кнопке «Смотреть».
 *
 * Сериализуемая: тот же тип лежит в очереди неотправленных на устройстве, и заводить для
 * очереди второй, «почти такой же», значило бы однажды разойтись с тем, что отправляется.
 */
@Serializable
data class ProblemPost(
    val kind: String,
    val text: String,
    val origin: String,
    val platform: String,
    val model: String,
    val os: String,
    val build: String,
    val stream: String,
    val nickname: String,
    val log: String,
)

/** Чем кончилась отправка отчёта. */
sealed interface ProblemSendResult {
    data class Sent(val number: String) : ProblemSendResult

    /** Не дошли до сервера. Отчёт при этом не теряется — он ложится в очередь. */
    data class NoConnection(val link: LinkState) : ProblemSendResult

    data class Refused(val status: Int, val code: String) : ProblemSendResult
}
