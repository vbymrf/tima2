package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.Appeals
import io.tima.domain.chat.EntityKind
import io.tima.domain.chat.EntityThreads
import io.tima.domain.chat.ThreadMessage
import io.tima.domain.chat.ThreadPage
import io.tima.domain.chat.ThreadRow
import io.tima.domain.chat.ThreadState
import io.tima.domain.chat.ThreadStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Переписка «сущность — человек» по HTTP (ПЛАН-(СЦ) §1.9, СЦ15; сервер `entity_threads.go`). */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class ThreadsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : EntityThreads {

    private fun base(kind: EntityKind, id: String): String = when (kind) {
        EntityKind.Group -> "/api/v1/groups/$id"
        EntityKind.Channel -> "/api/v1/channels/$id"
        else -> "/api/v1/communities/$id"
    }

    private val b64 = kotlin.io.encoding.Base64.UrlSafe.withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT_OPTIONAL)

    private suspend fun getJson(path: String): JsonObject? = try {
        val r = client.get(route.api(path)) { header("Authorization", "Bearer ${token()}") }
        if (r.status == HttpStatusCode.OK) r.jsonBody() else null
    } catch (_: Throwable) {
        null
    }

    private suspend fun send(method: String, path: String, body: JsonObject): Pair<HttpStatusCode, String>? = try {
        val r = if (method == "POST") {
            client.post(route.api(path)) { header("Authorization", "Bearer ${token()}"); contentType(ContentType.Application.Json); setBody(body.toString()) }
        } else {
            client.put(route.api(path)) { header("Authorization", "Bearer ${token()}"); contentType(ContentType.Application.Json); setBody(body.toString()) }
        }
        r.status to r.jsonBody().codeOf()
    } catch (_: Throwable) {
        null
    }

    private fun rowOf(o: JsonObject): ThreadRow? = ThreadRow(
        kind = EntityKind.fromWire(o.str("entity_kind").orEmpty()) ?: return null,
        entityId = o.str("entity_id").orEmpty(),
        userId = o.str("user_id").orEmpty(),
        title = o.str("title").orEmpty(),
        preview = o.str("preview").orEmpty(),
        lastAtMs = o.str("last_at")?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0,
        unread = o["unread"]?.jsonPrimitive?.intOrNull ?: 0,
        state = ThreadState.fromWire(o.str("state").orEmpty()),
        blocked = o.bool("blocked") ?: false,
    )

    private fun pageOf(o: JsonObject, kind: EntityKind, id: String): ThreadPage {
        val thread = o["thread"]?.jsonObjectOrNull()
        return ThreadPage(
            kind = EntityKind.fromWire(o.str("entity_kind").orEmpty()) ?: kind,
            entityId = o.str("entity_id") ?: id,
            accepts = o.bool("accepts") ?: true,
            state = ThreadState.fromWire(thread?.str("state").orEmpty()),
            blocked = thread?.bool("blocked") ?: false,
            messages = o["messages"]?.jsonArrayOrNull()?.mapNotNull { e ->
                val m = e.jsonObjectOrNull() ?: return@mapNotNull null
                ThreadMessage(
                    messageId = m["message_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    fromEntity = m.str("side") == "entity",
                    body = runCatching { b64.decode(m.str("body").orEmpty()) }.getOrDefault(ByteArray(0)),
                    preview = m.str("preview").orEmpty(),
                    atMs = m["at_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    authorId = m.str("author_id"),
                )
            }.orEmpty(),
        )
    }

    private fun stepOf(answer: Pair<HttpStatusCode, String>?): ThreadStep = when {
        answer == null -> ThreadStep.Offline
        answer.first == HttpStatusCode.Created -> ThreadStep.Sent
        answer.second == "not_accepting" -> ThreadStep.NotAccepting
        answer.second == "blocked_by_entity" || answer.second == "blocked_by_user" -> ThreadStep.Blocked
        answer.second == "people_limit" -> ThreadStep.PeopleLimit
        else -> ThreadStep.Refused(answer.second)
    }

    private fun message(body: ByteArray, preview: String) = buildJsonObject {
        put("body", JsonPrimitive(b64.encode(body)))
        put("preview", JsonPrimitive(preview))
    }

    override suspend fun mine(): List<ThreadRow>? =
        getJson("/api/v1/users/me/threads")?.get("threads")?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull()?.let(::rowOf) }

    override suspend fun mineWith(kind: EntityKind, id: String): ThreadPage? =
        getJson(base(kind, id) + "/thread/messages")?.let { pageOf(it, kind, id) }

    override suspend fun write(kind: EntityKind, id: String, body: ByteArray, preview: String): ThreadStep =
        stepOf(send("POST", base(kind, id) + "/thread/messages", message(body, preview)))

    override suspend fun setState(kind: EntityKind, id: String, state: ThreadState): Boolean =
        send("PUT", base(kind, id) + "/thread/state", buildJsonObject { put("state", JsonPrimitive(state.wire)) })?.first == HttpStatusCode.NoContent

    override suspend fun read(kind: EntityKind, id: String, upTo: Long): Boolean =
        send("PUT", base(kind, id) + "/thread/read", buildJsonObject { put("up_to", JsonPrimitive(upTo)) })?.first == HttpStatusCode.NoContent

    override suspend fun appeals(kind: EntityKind, id: String): Appeals? {
        val o = getJson(base(kind, id) + "/threads") ?: return null
        return Appeals(
            rows = o["threads"]?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull()?.let(::rowOf) }.orEmpty(),
            accepts = o.bool("accepts") ?: true,
            peopleToday = o["people_today"]?.jsonPrimitive?.intOrNull ?: 0,
            peopleLimit = o["people_limit"]?.jsonPrimitive?.intOrNull ?: 5,
        )
    }

    override suspend fun withPerson(kind: EntityKind, id: String, userId: String): ThreadPage? =
        getJson(base(kind, id) + "/threads/$userId/messages")?.let { pageOf(it, kind, id) }

    override suspend fun writeAs(kind: EntityKind, id: String, userId: String, body: ByteArray, preview: String): ThreadStep =
        stepOf(send("POST", base(kind, id) + "/threads/$userId/messages", message(body, preview)))

    override suspend fun readAs(kind: EntityKind, id: String, userId: String, upTo: Long): Boolean =
        send("PUT", base(kind, id) + "/threads/$userId/read", buildJsonObject { put("up_to", JsonPrimitive(upTo)) })?.first == HttpStatusCode.NoContent

    override suspend fun block(kind: EntityKind, id: String, userId: String, blocked: Boolean): Boolean =
        send("PUT", base(kind, id) + "/threads/$userId/block", buildJsonObject { put("blocked", JsonPrimitive(blocked)) })?.first == HttpStatusCode.NoContent

    override suspend fun setAccepts(kind: EntityKind, id: String, accept: Boolean): Boolean =
        send("PUT", base(kind, id) + "/appeals", buildJsonObject { put("accept", JsonPrimitive(accept)) })?.first == HttpStatusCode.NoContent
}
