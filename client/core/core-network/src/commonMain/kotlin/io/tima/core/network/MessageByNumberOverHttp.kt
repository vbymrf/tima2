package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive

/**
 * Сообщение по номеру (ПЛАН-(РС) РС3, РС7): `GET /groups/{id}/messages/{n}`, `/channels/{id}/posts/{n}`,
 * `/chats/{id}/messages/{n}`. Ответ — сообщение или заглушка «недоступно» с причиной.
 */
class MessageByNumberOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    sealed interface Answer {
        /** Сообщение группы — кадром, тем же разбором с подписью, что лента. */
        class GroupFrame(val stored: ByteArray) : Answer
        /** Запись канала — открытыми узлами и разметкой. */
        class Open(val authorId: String?, val nodes: List<String>, val markup: String, val atMs: Long) : Answer
        /** Личное с обёрткой для этого устройства — его открывает приёмник; здесь не разбирается. */
        data object Personal : Answer
        class Unavailable(val reason: String, val level: Int?) : Answer
        data object Offline : Answer
    }

    suspend fun find(kind: String, chatId: String, serverId: Long): Answer {
        val path = when (kind) {
            "group" -> "/api/v1/groups/$chatId/messages/$serverId"
            "channel" -> "/api/v1/channels/$chatId/posts/$serverId"
            else -> "/api/v1/chats/$chatId/messages/$serverId"
        }
        val response = try {
            client.get(route.api(path)) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return Answer.Offline
        }
        if (response.status != HttpStatusCode.OK) return Answer.Unavailable("closed", null)
        val body = response.jsonBody() ?: return Answer.Offline
        body["unavailable"]?.jsonObjectOrNull()?.let { stub ->
            return Answer.Unavailable(stub.str("reason") ?: "closed", stub.int("level"))
        }
        val message = body["message"]?.jsonObjectOrNull() ?: return Answer.Unavailable("missing", null)
        return when (kind) {
            "group" -> Answer.GroupFrame(io.tima.core.network.GroupFrame.toStored(message.toString()))
            "channel" -> Answer.Open(
                authorId = message.str("author_id"),
                nodes = message["nodes"]?.jsonArrayOrNull().orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                markup = message["markup"]?.let { m -> (m as? JsonPrimitive)?.content ?: m.toString() }.orEmpty(),
                atMs = message.long("created_at_unix_ms") ?: 0,
            )
            else -> Answer.Personal
        }
    }
}
