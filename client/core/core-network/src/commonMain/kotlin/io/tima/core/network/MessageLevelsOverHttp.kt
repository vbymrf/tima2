package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.MessageLevels
import io.tima.domain.chat.NarrowStep

/**
 * Сужение круга сообщения — `PATCH /groups/{id}/messages/{messageID}` (ADR-0019 §6).
 *
 * Расширение сюда не доходит: его отвергает доменный случай, и это не дублирование
 * серверной проверки, а разные её назначения. Сервер защищает данные от чужого клиента;
 * домен — человека от бессмысленного нажатия.
 */
class MessageLevelsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : MessageLevels {

    override suspend fun narrow(groupId: String, messageId: Long, level: Int): NarrowStep {
        val response = try {
            client.patch(route.api("/api/v1/groups/$groupId/messages/$messageId")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("{\"level\":$level}")
            }
        } catch (e: Throwable) {
            return NarrowStep.Offline(classifyFailure(e).retryDelayMs)
        }

        val body = response.jsonBody()
        return when {
            response.status == HttpStatusCode.OK ->
                NarrowStep.Narrowed(body?.int("level") ?: level)

            // Сервер отверг расширение. До этого места оно доходит только у клиента,
            // который считает круг сообщения не тем, что он есть, — например строка на
            // экране устарела. Тогда честно сказать «уже уже», а не «ошибка сервера».
            body.codeOf() == "cannot_widen" -> NarrowStep.Wider
            body.codeOf() == "cannot_encrypt_later" -> NarrowStep.CannotEncryptLater
            response.status == HttpStatusCode.Forbidden -> NarrowStep.NotAllowed
            response.status == HttpStatusCode.NotFound -> NarrowStep.NotFound
            else -> NarrowStep.Refused(body.codeOf())
        }
    }
}
