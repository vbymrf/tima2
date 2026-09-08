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
import io.tima.domain.chat.Communities
import io.tima.domain.chat.CommunityItem
import io.tima.domain.chat.CommunityKinds
import io.tima.domain.chat.CommunityPage
import io.tima.domain.chat.CommunityStep
import io.tima.domain.chat.LinkStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray

/**
 * Сообщества по HTTP (ПЛАН-СООБЩЕСТВ С5…С7).
 *
 * **Что можно внести — собирается из двух готовых списков**, а не спрашивается отдельной
 * ручкой: «свои группы» и «свои каналы» сервер уже отдаёт, и третий список тех же вещей
 * был бы третьим местом, где они могут разойтись. Отбор — свои и ещё не связанные — идёт
 * по полям `owner_id`/`community_id` из тех же ответов.
 */
class CommunitiesOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
    /** Кто мы: «своё» значит «где я владелец». */
    private val me: () -> String,
) : Communities {

    override suspend fun create(title: String): CommunityStep {
        val response = try {
            client.post(route.api("/api/v1/communities")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("title", JsonPrimitive(title)) }.toString())
            }
        } catch (e: Throwable) {
            return CommunityStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return if (response.status == HttpStatusCode.Created) {
            CommunityStep.Created(body?.str("community_id").orEmpty())
        } else {
            CommunityStep.Refused(body.codeOf())
        }
    }

    override suspend fun describe(communityId: String, text: String): Boolean {
        val response = try {
            client.post(route.api("/api/v1/communities/$communityId/messages")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("text", JsonPrimitive(text)) }.toString())
            }
        } catch (e: Throwable) {
            return false
        }
        return response.status == HttpStatusCode.Created
    }

    override suspend fun link(communityId: String, kind: String, itemId: String): LinkStep {
        val response = try {
            client.post(route.api("/api/v1/communities/$communityId/items")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("{\"kind\":\"$kind\",\"id\":\"$itemId\"}")
            }
        } catch (e: Throwable) {
            return LinkStep.Failed
        }
        return when (response.status) {
            HttpStatusCode.Created -> LinkStep.Linked
            HttpStatusCode.Conflict -> LinkStep.Busy
            HttpStatusCode.Forbidden -> LinkStep.NotAllowed
            else -> LinkStep.Failed
        }
    }

    override suspend fun unlink(communityId: String, kind: String, itemId: String): LinkStep {
        val response = try {
            client.delete(route.api("/api/v1/communities/$communityId/items/$kind/$itemId")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return LinkStep.Failed
        }
        return when (response.status) {
            HttpStatusCode.NoContent -> LinkStep.Linked
            HttpStatusCode.Forbidden -> LinkStep.NotAllowed
            else -> LinkStep.Failed
        }
    }

    override suspend fun page(communityId: String): CommunityPage? {
        val body = try {
            val response = client.get(route.api("/api/v1/communities/$communityId")) {
                header("Authorization", "Bearer ${token()}")
            }
            if (response.status != HttpStatusCode.OK) return null
            response.jsonBody()
        } catch (e: Throwable) {
            return null
        } ?: return null

        val community = runCatching { body["community"]?.jsonObjectOrNull() }.getOrNull() ?: return null
        return CommunityPage(
            communityId = community.str("community_id").orEmpty(),
            title = community.str("title").orEmpty(),
            owner = community.bool("owner") ?: false,
            admin = community.bool("admin") ?: false,
            subscribed = community.bool("subscribed") ?: false,
            description = runCatching { body["description"]?.jsonArray }.getOrNull()
                ?.mapNotNull { it.jsonObjectOrNull()?.firstNode() }
                ?: emptyList(),
            items = runCatching { body["items"]?.jsonArray }.getOrNull()
                ?.mapNotNull { element ->
                    val row = element.jsonObjectOrNull() ?: return@mapNotNull null
                    CommunityItem(
                        kind = row.str("kind").orEmpty(),
                        id = row.str("id").orEmpty(),
                        title = row.str("title").orEmpty(),
                        personal = row.bool("personal") ?: false,
                    )
                } ?: emptyList(),
        )
    }

    override suspend fun mine(): List<CommunityPage> {
        val body = try {
            val response = client.get(route.api("/api/v1/communities")) {
                header("Authorization", "Bearer ${token()}")
            }
            if (response.status != HttpStatusCode.OK) return emptyList()
            response.jsonBody()
        } catch (e: Throwable) {
            return emptyList()
        } ?: return emptyList()

        return runCatching { body["communities"]?.jsonArray }.getOrNull()
            ?.mapNotNull { element ->
                val row = element.jsonObjectOrNull() ?: return@mapNotNull null
                CommunityPage(
                    communityId = row.str("community_id").orEmpty(),
                    title = row.str("title").orEmpty(),
                    owner = row.bool("owner") ?: false,
                    admin = row.bool("admin") ?: false,
                    subscribed = row.bool("subscribed") ?: false,
                )
            } ?: emptyList()
    }

    override suspend fun linkable(): List<CommunityItem> {
        val mine = me()
        val groups = list("/api/v1/groups", "groups") { row ->
            CommunityItem(
                kind = CommunityKinds.GROUP,
                id = row.str("group_id").orEmpty(),
                title = row.str("title").orEmpty(),
                personal = row.str("kind") == "private",
            ).takeIf { row.str("owner_id") == mine && row.str("community_id").isNullOrEmpty() }
        }
        val channels = list("/api/v1/channels", "channels") { row ->
            CommunityItem(
                kind = CommunityKinds.CHANNEL,
                id = row.str("channel_id").orEmpty(),
                title = row.str("title").orEmpty(),
            ).takeIf { row.str("owner_id") == mine && row.str("community_id").isNullOrEmpty() }
        }
        return groups + channels
    }

    override suspend fun subscribe(communityId: String, on: Boolean): Boolean {
        val path = "/api/v1/communities/$communityId/subscribe"
        val response = try {
            if (on) {
                client.post(route.api(path)) { header("Authorization", "Bearer ${token()}") }
            } else {
                client.delete(route.api(path)) { header("Authorization", "Bearer ${token()}") }
            }
        } catch (e: Throwable) {
            return false
        }
        return response.status == HttpStatusCode.OK
    }

    private suspend fun list(
        path: String,
        field: String,
        row: (JsonObject) -> CommunityItem?,
    ): List<CommunityItem> {
        val body = try {
            val response = client.get(route.api(path)) { header("Authorization", "Bearer ${token()}") }
            if (response.status != HttpStatusCode.OK) return emptyList()
            response.jsonBody()
        } catch (e: Throwable) {
            return emptyList()
        } ?: return emptyList()
        return runCatching { body[field]?.jsonArray }.getOrNull()
            ?.mapNotNull { it.jsonObjectOrNull()?.let(row) }
            ?: emptyList()
    }

    /**
     * Первый узел описания: сообщение уровня 0 показывается строкой.
     *
     * Узлы — массив строк (ADR-0011 §4). Берётся первый: описание сообщества это один
     * абзац, а не документ, и склеивать здесь несколько узлов значило бы решать за экран.
     */
    private fun JsonObject.firstNode(): String? =
        runCatching { (this["nodes"]?.jsonArray?.firstOrNull() as? JsonPrimitive)?.content }.getOrNull()
}
