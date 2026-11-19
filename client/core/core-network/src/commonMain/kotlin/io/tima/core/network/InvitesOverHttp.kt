package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.EntityInvite
import io.tima.domain.chat.EntityInvites
import io.tima.domain.chat.EntityKind
import io.tima.domain.chat.EntitySecrecy
import io.tima.domain.chat.InviteStep
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Приглашения и «секретная» по HTTP (doc_mig/ПЛАН-(СЦ)-СОЦИУМА.md, СЦ2; сервер `entity_invites.go`,
 * `entity_secret.go`).
 */
class InvitesOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : EntityInvites, EntitySecrecy {

    private fun path(kind: EntityKind, id: String): String = when (kind) {
        EntityKind.Group -> "/api/v1/groups/$id"
        EntityKind.Channel -> "/api/v1/channels/$id"
        EntityKind.Community -> "/api/v1/communities/$id"
        EntityKind.Voice -> "/api/v1/voice-rooms/$id"
    }

    override suspend fun invite(kind: EntityKind, entityId: String, userId: String): InviteStep {
        val response = try {
            client.post(route.api(path(kind, entityId) + "/invites")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("user_id", JsonPrimitive(userId)) }.toString())
            }
        } catch (_: Throwable) {
            return InviteStep.Offline
        }
        val body = response.jsonBody()
        return when (response.status) {
            HttpStatusCode.Created -> body?.let(::inviteOf)?.let { InviteStep.Sent(it) } ?: InviteStep.Refused("201 без приглашения")
            HttpStatusCode.Conflict -> InviteStep.Already
            HttpStatusCode.Forbidden -> InviteStep.Forbidden
            HttpStatusCode.NotFound -> InviteStep.Gone
            else -> InviteStep.Refused(body.codeOf())
        }
    }

    override suspend fun mine(): List<EntityInvite>? {
        val response = try {
            client.get(route.api("/api/v1/users/me/invites")) { header("Authorization", "Bearer ${token()}") }
        } catch (_: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        return response.jsonBody()?.get("invites")?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull()?.let(::inviteOf) }
    }

    override suspend fun answer(inviteId: String, accept: Boolean): InviteStep {
        val response = try {
            client.put(route.api("/api/v1/invites/$inviteId")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("accept", JsonPrimitive(accept)) }.toString())
            }
        } catch (_: Throwable) {
            return InviteStep.Offline
        }
        return when (response.status) {
            HttpStatusCode.OK -> InviteStep.Answered
            HttpStatusCode.NotFound, HttpStatusCode.Gone -> InviteStep.Gone
            else -> InviteStep.Refused(response.jsonBody().codeOf())
        }
    }

    /** У группы — тем же PATCH, что прочие настройки; у канала и сообщества — `PUT …/hidden`. */
    override suspend fun set(kind: EntityKind, id: String, secret: Boolean): Boolean = try {
        val body = buildJsonObject { put("hidden", JsonPrimitive(secret)) }.toString()
        val response = if (kind == EntityKind.Group) {
            client.patch(route.api(path(kind, id))) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } else {
            client.put(route.api(path(kind, id) + "/hidden")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        response.status == HttpStatusCode.OK
    } catch (_: Throwable) {
        false
    }

    /** Переименовать группу (ПЛАН-(СЦ) СЦ5, «Основное»): тем же PATCH, что прочие настройки. */
    suspend fun rename(groupId: String, title: String): Boolean = try {
        client.patch(route.api(path(EntityKind.Group, groupId))) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("title", JsonPrimitive(title)) }.toString())
        }.status == HttpStatusCode.OK
    } catch (_: Throwable) {
        false
    }

    private fun inviteOf(o: JsonObject): EntityInvite? {
        val id = o.str("invite_id") ?: return null
        val kind = EntityKind.fromWire(o.str("entity_kind").orEmpty()) ?: return null
        return EntityInvite(
            inviteId = id,
            kind = kind,
            entityId = o.str("entity_id").orEmpty(),
            inviterId = o.str("inviter_id").orEmpty(),
            title = o.str("title").orEmpty(),
            secret = o.bool("hidden") ?: false,
            personal = o.bool("personal") ?: false,
            expiresAt = o.str("expires_at").orEmpty(),
        )
    }
}
