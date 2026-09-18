package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.AccountStorePort
import io.tima.domain.chat.AccountStoreStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Ячейка копии книги у сервера — `GET/PUT /users/me/store/book` (Р2а, миграция сервера 0051).
 *
 * 204 — копии ещё нет. 409 — ревизия не следующая, и в ответе лежит текущее: отдаём его
 * наружу, чтобы клиент не ходил дважды. Устройство серверу не шлём — он берёт его из токена.
 */
class AccountStoreOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
    private val kind: String = "book",
) : AccountStorePort {

    override suspend fun fetch(): AccountStoreStep = try {
        val response = client.get(route.api("/api/v1/users/me/store/$kind")) {
            header("Authorization", "Bearer ${token()}")
        }
        when (response.status) {
            HttpStatusCode.NoContent -> AccountStoreStep.Empty
            HttpStatusCode.OK -> parseBlob(response.bodyAsText()) ?: AccountStoreStep.Refused("копия не разобралась")
            else -> AccountStoreStep.Refused(errorCode(response.bodyAsText(), response.status.value))
        }
    } catch (e: Exception) {
        AccountStoreStep.Offline
    }

    override suspend fun put(revision: Long, blob: ByteArray): AccountStoreStep = try {
        val response = client.put(route.api("/api/v1/users/me/store/$kind")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody("""{"revision":$revision,"blob":"${encodeBase64Url(blob)}"}""")
        }
        when (response.status) {
            HttpStatusCode.OK -> AccountStoreStep.Stored
            HttpStatusCode.Conflict -> {
                val current = parseBlob(response.bodyAsText())
                if (current != null) AccountStoreStep.Conflict(current) else AccountStoreStep.Refused("revision_conflict без текущего")
            }
            else -> AccountStoreStep.Refused(errorCode(response.bodyAsText(), response.status.value))
        }
    } catch (e: Exception) {
        AccountStoreStep.Offline
    }

    /** Служебная группа аккаунта — `GET /users/me/store/group`; сервер заводит её при первом вопросе. */
    suspend fun storeGroup(): String? = try {
        val response = client.get(route.api("/api/v1/users/me/store/group")) {
            header("Authorization", "Bearer ${token()}")
        }
        if (response.status != HttpStatusCode.OK) null
        else Json.parseToJsonElement(response.bodyAsText()).jsonObject["group_id"]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }

    private fun parseBlob(body: String): AccountStoreStep.Blob? = runCatching {
        val o = Json.parseToJsonElement(body).jsonObject
        val revision = o["revision"]?.jsonPrimitive?.longOrNull ?: return null
        val bytes = decodeBase64Url(o["blob"]?.jsonPrimitive?.content ?: return null) ?: return null
        AccountStoreStep.Blob(revision, o["device_id"]?.jsonPrimitive?.content ?: "", bytes)
    }.getOrNull()

    private fun errorCode(body: String, status: Int): String =
        runCatching { Json.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull() ?: "http_$status"
}
