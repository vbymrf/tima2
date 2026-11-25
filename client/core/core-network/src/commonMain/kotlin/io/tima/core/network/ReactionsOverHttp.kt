package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.CommentEntry
import io.tima.domain.chat.CommentStep
import io.tima.domain.chat.CommentsStep
import io.tima.domain.chat.PostComments
import io.tima.domain.chat.Reacting
import io.tima.domain.chat.Reactions
import io.tima.domain.chat.ShowcaseSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Эмоции и пальцы (ПЛАН-(СЦ) §1.18, СЦ37–СЦ40): `PUT|DELETE /reactions/{вид}/{id}/{номер}` — своя эмоция у записи
 * канала и у сообщения уровня 0 («Описание») группы, канала, сообщества; `PUT /votes/channel/{id}/{номер}` — свой
 * палец у записи канала. Настройки «Описания» — `PUT /{вид}/{id}/showcase-settings`.
 */
class ReactionsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : Reacting {

    override suspend fun react(kind: String, entityId: String, messageId: Long, emotion: String?): Reactions? {
        val path = route.api("/api/v1/reactions/$kind/$entityId/$messageId")
        val response = try {
            if (emotion == null) {
                client.delete(path) { header("Authorization", "Bearer ${token()}") }
            } else {
                client.put(path) {
                    header("Authorization", "Bearer ${token()}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("emotion", JsonPrimitive(emotion)) }.toString())
                }
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        return Reactions(emotionCountsOf(body), body.str("mine")?.takeIf { it.isNotEmpty() })
    }

    override suspend fun vote(channelId: String, postId: Long, vote: Int): Boolean {
        val response = try {
            client.put(route.api("/api/v1/votes/channel/$channelId/$postId")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("vote", JsonPrimitive(vote)) }.toString())
            }
        } catch (e: Throwable) {
            return false
        }
        return response.status == HttpStatusCode.OK
    }

    /** Закреплённая запись канала (ПЛАН-(СЦ) §1.18, 8): номер, 0 — нет; `null` — сеть или отказ. */
    suspend fun channelPin(channelId: String): Long? {
        val response = try {
            client.get(route.api("/api/v1/channels/$channelId/pin")) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        return response.jsonBody()?.long("message_id") ?: 0L
    }

    /** Закрепить запись канала ([postId]) или снять (`null`). Закрепляют владелец, админ, модератор. */
    suspend fun setChannelPin(channelId: String, postId: Long?): Boolean {
        val path = route.api("/api/v1/channels/$channelId/pin")
        val response = try {
            if (postId == null) {
                client.delete(path) { header("Authorization", "Bearer ${token()}") }
            } else {
                client.put(path) {
                    header("Authorization", "Bearer ${token()}")
                    contentType(ContentType.Application.Json)
                    setBody(buildJsonObject { put("message_id", JsonPrimitive(postId)) }.toString())
                }
            }
        } catch (e: Throwable) {
            return false
        }
        return response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent
    }

    /** Поменять настройки «Описания» (`null` — оставить). Ответ — настройки после; `null` — сеть или отказ. */
    suspend fun showcaseSettings(kind: String, entityId: String, whole: Boolean? = null, comments: Boolean? = null): ShowcaseSettings? {
        val plural = when (kind) {
            "channel" -> "channels"
            "community" -> "communities"
            else -> "groups"
        }
        val response = try {
            client.put(route.api("/api/v1/$plural/$entityId/showcase-settings")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        whole?.let { put("whole", JsonPrimitive(it)) }
                        comments?.let { put("comments", JsonPrimitive(it)) }
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        return showcaseSettingsOf(response.jsonBody())
    }
}

/** Настройки «Описания» из ответа (`{whole, comments}`); нет — умолчания: обрезкой, без комментариев. */
fun showcaseSettingsOf(o: JsonObject?): ShowcaseSettings =
    ShowcaseSettings(whole = o?.bool("whole") ?: false, comments = o?.bool("comments") ?: false)

/**
 * Комментарии к сообщению уровня 0 («Описание») группы или сообщества (§1.18, 11) — тем же портом, что комментарии
 * записи канала: окно комментариев одно. `channelId` здесь — номер сущности, `postId` — номер сообщения уровня 0.
 * Пишет участник при включённой настройке; читает каждый, кто видит «Описание». Убрать комментарий здесь нельзя.
 */
class Level0CommentsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
    /** `group` или `community`. */
    private val kind: String,
) : PostComments {

    private fun path(entityId: String, messageId: Long) = "/api/v1/level0/$kind/$entityId/$messageId/comments"

    override suspend fun under(channelId: String, postId: Long, after: Long): CommentsStep {
        val query = if (after > 0) "?after=$after" else ""
        val response = try {
            client.get(route.api(path(channelId, postId) + query)) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return CommentsStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return when (response.status) {
            HttpStatusCode.OK -> CommentsStep.Conversation(
                entries = body?.get("comments")?.jsonArrayOrNull().orEmpty().mapNotNull { el ->
                    val row = el.jsonObjectOrNull() ?: return@mapNotNull null
                    CommentEntry(
                        postId = row.long("comment_id") ?: return@mapNotNull null,
                        authorId = row.str("author_id").orEmpty(),
                        text = row.str("text").orEmpty(),
                        atMs = row.long("created_at_unix_ms") ?: 0,
                        parentPostId = postId,
                    )
                },
                level = 0,
            )
            HttpStatusCode.NotFound -> CommentsStep.NoRoot
            else -> CommentsStep.Refused(body.codeOf())
        }
    }

    override suspend fun write(channelId: String, postId: Long, text: String): CommentStep {
        val response = try {
            client.post(route.api(path(channelId, postId))) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("text", JsonPrimitive(text)) }.toString())
            }
        } catch (e: Throwable) {
            return CommentStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return when {
            response.status == HttpStatusCode.Created -> CommentStep.Written(body?.long("comment_id") ?: 0)
            body.codeOf() == "comments_closed" -> CommentStep.Closed
            response.status == HttpStatusCode.NotFound -> CommentStep.NoRoot
            else -> CommentStep.Refused(body.codeOf())
        }
    }

    override suspend fun remove(channelId: String, postId: Long): CommentStep = CommentStep.Refused("not_supported")
}
