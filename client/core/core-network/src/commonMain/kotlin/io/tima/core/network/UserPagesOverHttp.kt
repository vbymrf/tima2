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
import io.tima.domain.chat.CarryStep
import io.tima.domain.chat.MessageBodyCodec
import io.tima.domain.chat.PageEntry
import io.tima.domain.chat.PageStep
import io.tima.domain.chat.UserPages
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Страница человека по HTTP: своя лента, чужая лента и перенос к себе.
 *
 * ── ТЕКСТ БЕРЁТСЯ ИЗ ОРИГИНАЛА ──────────────────────────────────────────────
 *
 * У принесённой записи сервер отдаёт содержимое оригинала как есть — те же байты, что
 * были подписаны автором. Разбирает их тот же кодек, что и открытые сообщения групп:
 * второе представление текста завело бы второй способ его испортить.
 *
 * ── ЧЕГО ЗДЕСЬ ПОКА НЕТ, И ЭТО НАЗВАНО ──────────────────────────────────────
 *
 * **Подпись автора на странице не проверяется.** Для проверки нужен ключ устройства,
 * которым запись подписана, а он спрашивается по цепочке «участник группы → его
 * устройства» — то есть ровно то, чего у читателя чужой страницы нет. Пока текст
 * показывается как пришёл: контур публичный, сервер его и так видит. Проверку подписи на
 * странице заводить придётся вместе с раздачей ключей устройств вне группы, и это
 * отдельная работа, а не строчка здесь.
 */
class UserPagesOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
    private val codec: MessageBodyCodec,
) : UserPages {

    override suspend fun carry(groupId: String, messageId: Long, level: Int): CarryStep {
        val response = try {
            client.post(route.api("/api/v1/users/me/feed/items")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("{\"group_id\":\"$groupId\",\"message_id\":$messageId,\"level\":$level}")
            }
        } catch (e: Throwable) {
            return CarryStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return when {
            response.status == HttpStatusCode.Created ->
                CarryStep.Carried(body?.long("post_id") ?: 0)

            body.codeOf() == "cannot_carry" -> CarryStep.CannotCarry
            response.status == HttpStatusCode.NotFound -> CarryStep.NotFound
            else -> CarryStep.Refused(body.codeOf())
        }
    }

    override suspend fun page(userId: String): PageStep {
        val path = if (userId == "me") "/api/v1/users/me/feed" else "/api/v1/users/$userId/feed"
        val response = try {
            client.get(route.api(path)) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return PageStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return when {
            response.status == HttpStatusCode.OK -> PageStep.Page(entriesOf(body))
            response.status == HttpStatusCode.NotFound -> PageStep.NoPage
            else -> PageStep.Refused(body.codeOf())
        }
    }

    override suspend fun remove(postId: Long): CarryStep {
        val response = try {
            client.delete(route.api("/api/v1/users/me/feed/items/$postId")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return CarryStep.Offline(classifyFailure(e).retryDelayMs)
        }
        return when (response.status) {
            HttpStatusCode.NoContent -> CarryStep.Carried(postId)
            HttpStatusCode.NotFound -> CarryStep.NotFound
            else -> CarryStep.Refused(response.jsonBody().codeOf())
        }
    }

    private fun entriesOf(body: JsonObject?): List<PageEntry> {
        val items = runCatching { body?.get("items")?.jsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { element ->
            val row = element.jsonObjectOrNull() ?: return@mapNotNull null
            PageEntry(
                postId = row.long("post_id") ?: return@mapNotNull null,
                level = row.int("level") ?: 1,
                atMs = row.long("created_at_unix_ms") ?: 0,
                authorId = row.str("author_id").orEmpty(),
                // Тело не разобралось — строка остаётся: человек должен видеть, что запись
                // на странице есть. Спрятать её значило бы соврать о содержимом страницы.
                text = row.str("payload")
                    ?.let { runCatching { decodeBase64Url(it) }.getOrNull() }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { codec.decodeText(it) },
                carriedBy = row.str("carried_by").orEmpty(),
                sourceTitle = row.str("source_title").orEmpty(),
                refGroupId = row.str("ref_group_id").orEmpty(),
                refMessageId = row.long("ref_message_id") ?: 0,
            )
        }
    }
}
