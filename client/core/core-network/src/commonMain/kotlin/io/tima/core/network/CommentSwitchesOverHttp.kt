package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.CommentSwitches
import io.tima.domain.chat.SwitchStep

/**
 * Выключатели обсуждения по HTTP (ADR-0024 §6, ПЛАН-КАНАЛОВ К7).
 *
 * Два маршрута, потому что два выключателя: канал целиком и одна запись. Один маршрут с
 * необязательным номером записи означал бы, что «канал» — это «запись с пустым номером», и
 * первая же ошибка в вызове выключила бы обсуждения везде.
 *
 * **`403` здесь не прячется под `404`**, в отличие от чтения: человек уже смотрит на свою
 * запись и жмёт кнопку. Отказ, неотличимый от отсутствия, оставил бы его жать её ещё раз.
 */
class CommentSwitchesOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : CommentSwitches {

    override suspend fun channel(channelId: String, enabled: Boolean): SwitchStep =
        switch("/api/v1/channels/$channelId/comments", "{\"enabled\":$enabled}")

    override suspend fun post(channelId: String, postId: Long, closed: Boolean): SwitchStep =
        switch("/api/v1/channels/$channelId/posts/$postId/comments", "{\"closed\":$closed}")

    private suspend fun switch(path: String, body: String): SwitchStep {
        val response = try {
            client.put(route.api(path)) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: Throwable) {
            return SwitchStep.Offline(classifyFailure(e).retryDelayMs)
        }
        return when (response.status) {
            HttpStatusCode.OK -> SwitchStep.Switched
            HttpStatusCode.Forbidden -> SwitchStep.NotAllowed
            HttpStatusCode.NotFound -> SwitchStep.NotFound
            else -> SwitchStep.Refused(response.jsonBody().codeOf())
        }
    }
}
