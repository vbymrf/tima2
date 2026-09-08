package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.CommentEntry
import io.tima.domain.chat.CommentStep
import io.tima.domain.chat.CommentsStep
import io.tima.domain.chat.PostComments
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Разговор под записью по HTTP (ADR-0024, ПЛАН-КАНАЛОВ К5).
 *
 * Адрес один на канал и на страницу человека: страница — это канал, который ищут по
 * человеку (решение заказчика 2026-09-04). Второй ручки для ленты не заводится.
 *
 * **Текст комментария приходит открытым.** Подписи у него нет и не предполагается: контур
 * публичный, сервер видит содержимое и так — то же, что у постов канала. Это отличает
 * комментарий от сообщения группы, где текст едет в `payload` с подписью автора.
 *
 * **`404` значит «записи нет» и ничего больше.** Сервер отвечает так и на удалённый
 * корень, и на непоказанный, и на попытку комментировать комментарий — намеренно
 * одинаково: отказ, отличающийся от отсутствия, сам сообщал бы, что запись есть.
 */
class PostCommentsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : PostComments {

    override suspend fun under(channelId: String, postId: Long, after: Long): CommentsStep {
        val query = if (after > 0) "?after=$after" else ""
        val response = try {
            client.get(route.api(path(channelId, postId) + query)) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return CommentsStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return when {
            response.status == HttpStatusCode.OK -> CommentsStep.Conversation(
                entries = entriesOf(body),
                // Круг разговора — круг корня. Сервер называет его прямо, чтобы экран
                // показал его строкой, а не вычислял из контейнера.
                level = body?.int("level") ?: 1,
            )

            response.status == HttpStatusCode.NotFound -> CommentsStep.NoRoot
            else -> CommentsStep.Refused(body.codeOf())
        }
    }

    override suspend fun write(channelId: String, postId: Long, text: String): CommentStep {
        val response = try {
            client.post(route.api(path(channelId, postId))) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                // Тело собирается сериализатором, а не склейкой строк: в комментарии
                // бывают кавычки и переводы строк, и вручную собранный JSON ломается о
                // первый же из них.
                setBody(buildJsonObject { put("text", JsonPrimitive(text)) }.toString())
            }
        } catch (e: Throwable) {
            return CommentStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return when {
            response.status == HttpStatusCode.Created -> CommentStep.Written(body?.long("post_id") ?: 0)
            body.codeOf() == "comments_closed" -> CommentStep.Closed
            response.status == HttpStatusCode.NotFound -> CommentStep.NoRoot
            else -> CommentStep.Refused(body.codeOf())
        }
    }

    override suspend fun remove(channelId: String, postId: Long): CommentStep {
        val response = try {
            client.delete(route.api("/api/v1/channels/$channelId/posts/$postId")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return CommentStep.Offline(classifyFailure(e).retryDelayMs)
        }
        return when (response.status) {
            HttpStatusCode.NoContent -> CommentStep.Written(postId)
            HttpStatusCode.NotFound -> CommentStep.NoRoot
            else -> CommentStep.Refused(response.jsonBody().codeOf())
        }
    }

    private fun path(channelId: String, postId: Long) =
        "/api/v1/channels/$channelId/posts/$postId/comments"

    private fun entriesOf(body: JsonObject?): List<CommentEntry> {
        val items = runCatching { body?.get("comments")?.jsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { element ->
            val row = element.jsonObjectOrNull() ?: return@mapNotNull null
            CommentEntry(
                postId = row.long("post_id") ?: return@mapNotNull null,
                authorId = row.str("author_id").orEmpty(),
                text = row.str("text").orEmpty(),
                atMs = row.long("created_at_unix_ms") ?: 0,
                parentPostId = row.long("parent_post_id") ?: 0,
            )
        }
    }
}
