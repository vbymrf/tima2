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
import io.tima.domain.chat.EntityKind
import io.tima.domain.chat.EntityOwnership
import io.tima.domain.chat.OwnershipLink
import io.tima.domain.chat.OwnershipOffer
import io.tima.domain.chat.OwnershipStep
import io.tima.domain.chat.OwnershipView
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Владение по ключу личности по HTTP (ПЛАН-(СЦ) §6, СЦ11; сервер `entity_ownership.go`). */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
class OwnershipOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : EntityOwnership {

    private fun base(kind: EntityKind, id: String): String = when (kind) {
        EntityKind.Group -> "/api/v1/groups/$id/ownership"
        EntityKind.Channel -> "/api/v1/channels/$id/ownership"
        else -> "/api/v1/communities/$id/ownership"
    }

    private fun b64(bytes: ByteArray): String = kotlin.io.encoding.Base64.UrlSafe.encode(bytes).trimEnd('=')

    override suspend fun chain(kind: EntityKind, id: String): OwnershipView? {
        val response = try {
            client.get(route.api(base(kind, id))) { header("Authorization", "Bearer ${token()}") }
        } catch (_: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        val links = body["chain"]?.jsonArrayOrNull()?.mapNotNull { e ->
            val o = e.jsonObjectOrNull() ?: return@mapNotNull null
            OwnershipLink(o["link_no"]?.jsonPrimitive?.intOrNull ?: 0, o.str("owner_id").orEmpty(), o.str("kind").orEmpty())
        }.orEmpty()
        return OwnershipView(
            links = links,
            canTransfer = body.bool("can_transfer") ?: false,
            nextLink = body["next_link"]?.jsonPrimitive?.intOrNull ?: (links.size + 1),
            offer = body["offer"]?.jsonObjectOrNull()?.let(::offerOf),
        )
    }

    override suspend fun offer(kind: EntityKind, id: String, toUserId: String, expiresUnix: Long, signature: ByteArray): OwnershipStep =
        send(base(kind, id) + "/offer", buildJsonObject {
            put("to_user_id", JsonPrimitive(toUserId))
            put("expires_unix", JsonPrimitive(expiresUnix))
            put("signature", JsonPrimitive(b64(signature)))
        })

    override suspend fun accept(kind: EntityKind, id: String, signature: ByteArray): OwnershipStep =
        send(base(kind, id) + "/accept", buildJsonObject { put("signature", JsonPrimitive(b64(signature))) })

    override suspend fun drop(kind: EntityKind, id: String): OwnershipStep = try {
        val response = client.delete(route.api(base(kind, id) + "/offer")) { header("Authorization", "Bearer ${token()}") }
        when (response.status) {
            HttpStatusCode.NoContent, HttpStatusCode.OK -> OwnershipStep.Done
            HttpStatusCode.NotFound -> OwnershipStep.Gone
            else -> OwnershipStep.Refused(response.jsonBody().codeOf())
        }
    } catch (_: Throwable) {
        OwnershipStep.Offline
    }

    override suspend fun offersToMe(): List<OwnershipOffer>? {
        val response = try {
            client.get(route.api("/api/v1/users/me/ownership-offers")) { header("Authorization", "Bearer ${token()}") }
        } catch (_: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        return response.jsonBody()?.get("offers")?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull()?.let(::offerOf) }
    }

    /**
     * Кто может принять владение (§1.2, 9): в группе — модераторы и админы, в канале — модераторы, в сообществе —
     * админы. Нет связи — пусто.
     */
    suspend fun staff(kind: EntityKind, id: String): List<String> = try {
        val path = when (kind) {
            EntityKind.Group -> "/api/v1/groups/$id/members"
            EntityKind.Channel -> "/api/v1/channels/$id/moderators"
            else -> "/api/v1/communities/$id/admins"
        }
        val response = client.get(route.api(path)) { header("Authorization", "Bearer ${token()}") }
        val body = if (response.status == HttpStatusCode.OK) response.jsonBody() else null
        when (kind) {
            EntityKind.Group -> body?.get("members")?.jsonArrayOrNull()?.mapNotNull { e ->
                val o = e.jsonObjectOrNull() ?: return@mapNotNull null
                o.str("user_id")?.takeIf { o.str("role") == "moderator" || o.str("role") == "admin" }
            }
            EntityKind.Channel -> body?.get("moderators")?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitive.content }
            else -> body?.get("admins")?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitive.content }
        }.orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    private suspend fun send(path: String, body: JsonObject): OwnershipStep {
        val response = try {
            client.post(route.api(path)) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (_: Throwable) {
            return OwnershipStep.Offline
        }
        val code = response.jsonBody().codeOf()
        return when {
            response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created -> OwnershipStep.Done
            code == "bad_signature" -> OwnershipStep.BadPhrase(false)
            code == "offer_dropped" -> OwnershipStep.BadPhrase(true)
            code == "receiver_role" -> OwnershipStep.ReceiverRole
            code == "no_identity" -> OwnershipStep.NoIdentity
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone || code == "chain_moved" -> OwnershipStep.Gone
            else -> OwnershipStep.Refused(code)
        }
    }

    private fun offerOf(o: JsonObject): OwnershipOffer? {
        val kind = EntityKind.fromWire(o.str("entity_kind").orEmpty()) ?: return null
        return OwnershipOffer(
            kind = kind,
            entityId = o.str("entity_id").orEmpty(),
            linkNo = o["link_no"]?.jsonPrimitive?.intOrNull ?: return null,
            fromId = o.str("from_id").orEmpty(),
            toId = o.str("to_id").orEmpty(),
            expiresUnix = o["expires_unix"]?.jsonPrimitive?.longOrNull ?: return null,
            title = o.str("title").orEmpty(),
        )
    }
}
