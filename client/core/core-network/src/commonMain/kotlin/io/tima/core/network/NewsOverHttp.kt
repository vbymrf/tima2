package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Сколько нового у аккаунта, который на устройстве сейчас не открыт, — `GET /api/v1/users/me/news`
 * (заказчик 2026-10-07: оранжевое число у аватара в панели переходов). Сервер считает сообщения в
 * журнале этого устройства после последнего подтверждённого номера, без своих. Зовётся входом
 * того самого аккаунта.
 *
 * `null` — не узнали (нет сети, сервер старый): число тогда не показывается, а не «0».
 */
class NewsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {
    suspend fun messages(): Int? = try {
        val response = client.get(route.api("/api/v1/users/me/news")) { header("Authorization", "Bearer ${token()}") }
        if (response.status != HttpStatusCode.OK) {
            null
        } else {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["messages"]?.jsonPrimitive?.int
        }
    } catch (e: Throwable) {
        null
    }
}
