package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.account.VirtualAccount
import io.tima.domain.account.VirtualCreateStep
import io.tima.domain.account.VirtualsApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Виртуальные аккаунты по HTTP — `/api/v1/users/me/virtuals` (ПЛАН-КОНТАКТОВ.md, Д10).
 *
 * **Один код ответа — один исход, и различать их обязан клиент.** Сервер отвечает 409 на
 * три разных вещи: ник занят, пять уже есть, у владельца нет ключа личности. Свести их к
 * «не получилось» значило бы сказать человеку, придумавшему занятый ник, что кончилось
 * место, — поэтому разбирается поле `error`, а не только статус.
 *
 * **Ключи уходят в base64url без выравнивания**: сервер разбирает `RawURLEncoding`, и
 * обычный base64 с `+`, `/` и `=` он отвергнет — выглядеть это будет как «наши ключи не
 * годятся».
 */
class VirtualsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : VirtualsApi {

    override suspend fun create(
        nickname: String,
        identityPub: ByteArray,
        signature: ByteArray,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        platform: String,
    ): VirtualCreateStep = try {
        val response = client.post(route.api("/api/v1/users/me/virtuals")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("nickname", JsonPrimitive(nickname))
                    put("identity_pub", JsonPrimitive(encodeBase64Url(identityPub)))
                    put("signature", JsonPrimitive(encodeBase64Url(signature)))
                    put("encryption_pub", JsonPrimitive(encodeBase64Url(encryptionPub)))
                    put("signing_pub", JsonPrimitive(encodeBase64Url(signingPub)))
                    put("platform", JsonPrimitive(platform))
                }.toString(),
            )
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        when (response.status) {
            HttpStatusCode.Created -> {
                val userId = body?.get("user_id")?.jsonPrimitive?.content
                val deviceId = body?.get("device_id")?.jsonPrimitive?.content
                val access = body?.get("access_token")?.jsonPrimitive?.content
                if (userId.isNullOrBlank() || deviceId.isNullOrBlank() || access.isNullOrBlank()) {
                    // Ответ без того, чем входить, равен неудаче: аккаунт заведён, а мы о
                    // нём знаем только то, что он есть. Сказать «готово» тут нельзя.
                    VirtualCreateStep.Offline
                } else {
                    VirtualCreateStep.Created(userId, deviceId, access)
                }
            }
            HttpStatusCode.Conflict -> when (body?.get("error")?.jsonPrimitive?.content) {
                "nickname_taken" -> VirtualCreateStep.NicknameTaken
                "too_many_virtuals" -> VirtualCreateStep.TooMany
                else -> VirtualCreateStep.NotAllowed
            }
            HttpStatusCode.BadRequest -> VirtualCreateStep.BadNickname
            HttpStatusCode.Forbidden -> when (body?.get("error")?.jsonPrimitive?.content) {
                "owner_is_virtual" -> VirtualCreateStep.NotAllowed
                else -> VirtualCreateStep.BadSignature
            }
            else -> VirtualCreateStep.Offline
        }
    } catch (_: Throwable) {
        VirtualCreateStep.Offline
    }

    override suspend fun mine(): List<VirtualAccount>? = try {
        val response = client.get(route.api("/api/v1/users/me/virtuals")) {
            header("Authorization", "Bearer ${token()}")
        }
        if (response.status != HttpStatusCode.OK) {
            null
        } else {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["virtuals"]
                ?.jsonArray.orEmpty()
                .mapNotNull { row ->
                    val obj = row.jsonObject
                    val userId = obj["user_id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    VirtualAccount(userId, obj["nickname"]?.jsonPrimitive?.content.orEmpty())
                }
        }
    } catch (_: Throwable) {
        null
    }
}
