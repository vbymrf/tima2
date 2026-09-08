package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.ChannelStep
import io.tima.domain.chat.Channels
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Каналы по HTTP: пока только создание (ПЛАН-СООБЩЕСТВ С5).
 *
 * Чтение канала клиенту пока негде показать — экрана каналов нет, — и заводить порт
 * «на будущее» здесь незачем: он тут же начнёт расходиться с тем, что понадобится.
 */
class ChannelsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : Channels {

    override suspend fun create(
        title: String,
        description: String,
        inCatalogue: Boolean,
        comments: Boolean,
    ): ChannelStep {
        val response = try {
            client.post(route.api("/api/v1/channels")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("title", JsonPrimitive(title))
                        put("description", JsonPrimitive(description))
                        put("is_public", JsonPrimitive(inCatalogue))
                        put("comments_enabled", JsonPrimitive(comments))
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return ChannelStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return if (response.status == HttpStatusCode.Created) {
            ChannelStep.Created(body?.str("channel_id").orEmpty())
        } else {
            ChannelStep.Refused(body.codeOf())
        }
    }
}
