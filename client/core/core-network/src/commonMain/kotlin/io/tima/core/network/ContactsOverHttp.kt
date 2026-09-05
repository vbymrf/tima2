package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.ContactDiscovery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.putJsonArray

/**
 * Сверка книги с сервером — `POST /api/v1/users/discover` (ПЛАН-КОНТАКТОВ.md, Д4).
 *
 * **«Сверяем, не читая» — не фигура речи.** На сервер уходит номер, но хранится он там
 * слепым индексом (`HMAC(pepper, E.164)`, миграция 0017), и по ответу сервер узнаёт лишь
 * то, что этот номер спрашивали. Открытого справочника «кто с кем знаком» из этого не
 * складывается.
 *
 * **Отказ — не пустой ответ.** Исключение уходит наверх, где сценарий синхронизации
 * превращает его в «без сети»: пустая карта означала бы «никого из них в TIMa нет», и
 * книга разом потеряла бы все отметки.
 */
class ContactsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : ContactDiscovery {

    override suspend fun discover(phones: List<String>): Map<String, String?> {
        if (phones.isEmpty()) return emptyMap()

        val response = client.post(route.api("/api/v1/users/discover")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    putJsonArray("phones") { phones.forEach { add(JsonPrimitive(it)) } }
                }.toString(),
            )
        }
        if (response.status != HttpStatusCode.OK) {
            error("discover: сервер ответил ${response.status.value}")
        }
        val matches = runCatching {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["matches"]?.jsonObject
        }.getOrNull().orEmpty()

        // В ответе только найденные. Ненайденные возвращаются с null явно: иначе книга
        // не отличит «его там нет» от «о нём не спрашивали», и отметка «в TIMa» осталась
        // бы висеть у того, кто ушёл.
        return phones.associateWith { phone ->
            matches[phone]?.jsonPrimitive?.content?.ifBlank { null }
        }
    }
}

private fun Map<String, JsonElement>?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
