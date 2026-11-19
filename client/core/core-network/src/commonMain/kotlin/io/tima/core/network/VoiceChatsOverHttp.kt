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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Звуковой чат сообщества с настройками (ПЛАН-(СЦ) СЦ7; сервер `community_voice.go`). */
data class VoiceChatInfo(
    val roomId: String,
    val title: String,
    val communityId: String,
    val groupId: String,
    val allowMute: Boolean,
    val hostAllowed: Boolean,
    val hostCanKick: Boolean,
    val maxParticipants: Int,
    val hostId: String,
    val present: List<String>,
)

/** Вход: комната LiveKit и чат. */
data class VoiceEntry(val chat: VoiceChatInfo, val room: String, val url: String, val token: String)

sealed interface VoiceEnterStep {
    data class Entered(val entry: VoiceEntry) : VoiceEnterStep
    data object Full : VoiceEnterStep
    data object Kicked : VoiceEnterStep
    data object Gone : VoiceEnterStep
    data object NoLiveKit : VoiceEnterStep
    data object Offline : VoiceEnterStep
}

class VoiceChatsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {
    private fun chatOf(o: JsonObject): VoiceChatInfo? = VoiceChatInfo(
        roomId = o.str("room_id") ?: return null,
        title = o.str("title").orEmpty(),
        communityId = o.str("community_id").orEmpty(),
        groupId = o.str("group_id").orEmpty(),
        allowMute = o.bool("allow_mute") ?: true,
        hostAllowed = o.bool("host_allowed") ?: true,
        hostCanKick = o.bool("host_can_kick") ?: false,
        maxParticipants = o["max_participants"]?.jsonPrimitive?.intOrNull ?: 50,
        hostId = o.str("host_id").orEmpty(),
        present = o["present"]?.jsonArrayOrNull()?.mapNotNull { it.jsonPrimitive.content }.orEmpty(),
    )

    suspend fun list(communityId: String): List<VoiceChatInfo>? = try {
        val r = client.get(route.api("/api/v1/communities/$communityId/voice-rooms")) { header("Authorization", "Bearer ${token()}") }
        if (r.status != HttpStatusCode.OK) null else r.jsonBody()?.get("rooms")?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull()?.let(::chatOf) }
    } catch (_: Throwable) {
        null
    }

    suspend fun create(communityId: String, title: String, groupId: String = ""): String? = try {
        val r = client.post(route.api("/api/v1/communities/$communityId/voice-rooms")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("title", JsonPrimitive(title)); put("group_id", JsonPrimitive(groupId)) }.toString())
        }
        if (r.status == HttpStatusCode.Created) r.jsonBody()?.str("room_id") else null
    } catch (_: Throwable) {
        null
    }

    suspend fun enter(roomId: String): VoiceEnterStep {
        val r = try {
            client.post(route.api("/api/v1/voice-rooms/$roomId/enter")) { header("Authorization", "Bearer ${token()}") }
        } catch (_: Throwable) {
            return VoiceEnterStep.Offline
        }
        val body = r.jsonBody()
        return when {
            r.status == HttpStatusCode.OK && body != null -> chatOf(body)?.let {
                VoiceEnterStep.Entered(VoiceEntry(it, body.str("room").orEmpty(), body.str("url").orEmpty(), body.str("token").orEmpty()))
            } ?: VoiceEnterStep.Gone
            body.codeOf() == "room_full" -> VoiceEnterStep.Full
            body.codeOf() == "kicked" -> VoiceEnterStep.Kicked
            r.status == HttpStatusCode.ServiceUnavailable -> VoiceEnterStep.NoLiveKit
            else -> VoiceEnterStep.Gone
        }
    }

    suspend fun leave(roomId: String): Boolean = postEmpty("/api/v1/voice-rooms/$roomId/leave")

    suspend fun settings(roomId: String, allowMute: Boolean, hostAllowed: Boolean, hostCanKick: Boolean, max: Int): Boolean = try {
        client.put(route.api("/api/v1/voice-rooms/$roomId/settings")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("allow_mute", JsonPrimitive(allowMute)); put("host_allowed", JsonPrimitive(hostAllowed))
                put("host_can_kick", JsonPrimitive(hostCanKick)); put("max_participants", JsonPrimitive(max))
            }.toString())
        }.status == HttpStatusCode.OK
    } catch (_: Throwable) {
        false
    }

    suspend fun host(roomId: String, userId: String): Boolean = postJson("/api/v1/voice-rooms/$roomId/host", "user_id", userId)

    suspend fun kick(roomId: String, userId: String): Boolean = postJson("/api/v1/voice-rooms/$roomId/kick", "user_id", userId)

    private suspend fun postEmpty(path: String): Boolean = try {
        client.post(route.api(path)) { header("Authorization", "Bearer ${token()}") }.status.value in 200..299
    } catch (_: Throwable) {
        false
    }

    private suspend fun postJson(path: String, key: String, value: String): Boolean = try {
        client.post(route.api(path)) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put(key, JsonPrimitive(value)) }.toString())
        }.status.value in 200..299
    } catch (_: Throwable) {
        false
    }
}
