package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.UserDirectory
import io.tima.domain.chat.UserLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.putJsonArray

/**
 * Справочник: кто скрывается за номером телефона — переходник к порту `domain-chat`.
 *
 * Контракт снят с `internal/api/auth.go`:
 * `GET /api/v1/users/lookup?phone=+7…` → `{user_id}`, либо `404 user_not_found`, либо
 * `400 bad_phone`. Имя спрашивается отдельно — `POST /api/v1/users/names {ids}` →
 * `{names:{id:name}, phones:{id:phone}}`, и **только для собеседников по перепискам**:
 * сервер не отдаёт имена посторонних, и это его решение, а не наше упущение.
 *
 * **«Не найден» — не отказ.** Человека, которого нет в TIMA, надо позвать, а не сообщать
 * ему об ошибке. Поэтому [UserLookup.NotFound] отдельный исход, а не `Refused`.
 */
class UsersApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : UserDirectory {

    override suspend fun byPhone(phone: String): UserLookup {
        val response = try {
            client.get(route.api("/api/v1/users/lookup")) {
                header("Authorization", "Bearer ${token()}")
                parameter("phone", phone)
            }
        } catch (e: Throwable) {
            return UserLookup.Offline(classifyFailure(e).retryDelayMs)
        }

        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        val code = body?.get("code")?.jsonPrimitive?.content
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.NotFound -> return UserLookup.NotFound
            HttpStatusCode.BadRequest -> return UserLookup.BadPhone(code ?: "номер не в формате E.164")
            else -> return UserLookup.Refused(code ?: "сервер отказал: ${response.status.value}")
        }

        val userId = body?.get("user_id")?.jsonPrimitive?.content
        if (userId.isNullOrBlank()) return UserLookup.Refused("в ответе нет user_id")

        // Имя — вторым запросом и **без права уронить первый**: не пришло, значит его нет,
        // и переписка заведётся с номером вместо имени. Отказаться начинать переписку
        // из-за неизвестного имени было бы хуже.
        return UserLookup.Found(userId = userId, name = name(userId))
    }

    /**
     * Имя или номер собеседника — чем назвать переписку.
     *
     * Сервер отдаёт номера **только по собеседникам своих переписок** (`PhonesOfChatPeers`),
     * и это его решение, а не наше упущение: справочник целиком он не раскрывает. Поэтому
     * ответ бывает пустым, и это не отказ — переписка называется тем, что известно.
     */
    suspend fun nameOrNumber(userId: String): String? = name(userId) ?: number(userId)

    /** Отображаемое имя, если сервер его знает и вправе отдать. */
    private suspend fun name(userId: String): String? {
        val response = try {
            client.post(route.api("/api/v1/users/names")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        putJsonArray("ids") { add(userId) }
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        return body?.get("names")?.let { (it as? JsonObject) }
            ?.get(userId)?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
    }

    /** Номер — вторым выбором: он известен только по собеседникам своих переписок. */
    private suspend fun number(userId: String): String? {
        val response = try {
            client.post(route.api("/api/v1/users/names")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { putJsonArray("ids") { add(userId) } }.toString())
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = runCatching { Json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
        return body?.get("phones")?.let { (it as? JsonObject) }
            ?.get(userId)?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
    }
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: String) {
    add(kotlinx.serialization.json.JsonPrimitive(value))
}
