package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.OpenBody
import io.tima.domain.chat.PagePosts
import io.tima.domain.chat.PublishStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Запись на своей странице — `POST /channels/{id}/posts` (страница — канал человека). Узлы и
 * разметка — открытым текстом: контур публичный (ADR-0011 §4).
 */
class PagePostsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : PagePosts {

    override suspend fun publish(channelId: String, body: OpenBody, level: Int): PublishStep {
        val response = try {
            client.post(route.api("/api/v1/channels/$channelId/posts")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("text", JsonPrimitive(body.text.ifBlank { " " }))
                        put("nodes", JsonArray(body.nodes.map(::JsonPrimitive)))
                        if (body.markup.isNotEmpty()) put("markup", JsonPrimitive(body.markup))
                        put("level", JsonPrimitive(level))
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return PublishStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val json = response.jsonBody()
        return if (response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK) {
            PublishStep.Published(json?.long("post_id") ?: json?.long("id") ?: 0)
        } else {
            PublishStep.Refused(json.codeOf())
        }
    }
}
