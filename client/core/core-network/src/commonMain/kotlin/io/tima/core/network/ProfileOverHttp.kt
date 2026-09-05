package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.NickStep
import io.tima.domain.chat.Profile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Профиль по HTTP — имя и ник (ПЛАН-КОНТАКТОВ.md, Д1 и Д8).
 *
 * **Имя и ник — разные ручки, и это не формальность.** Имя меняется свободно и никем не
 * проверяется; ник занимается, и занятый отвечает 409. Одна ручка на оба поля отвечала бы
 * «занято» там, где меняли имя.
 *
 * **Занятость спрашивается отдельно и до сохранения**: узнать о ней после отправки формы
 * значит потерять уже введённое.
 */
class ProfileOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : Profile {

    override suspend fun setName(name: String): Boolean = try {
        client.patch(route.api("/api/v1/users/me/name")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", JsonPrimitive(name)) }.toString())
        }.status == HttpStatusCode.OK
    } catch (_: Throwable) {
        false
    }

    override suspend fun freeNickname(nick: String): Boolean? = try {
        val response = client.get(route.api("/api/v1/nicknames/$nick/free")) {
            header("Authorization", "Bearer ${token()}")
        }
        if (response.status != HttpStatusCode.OK) {
            // 400 — ник не проходит границы. Это не «занят» и не «свободен»: экран
            // говорит о границах, а не о занятости.
            null
        } else {
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["free"]?.jsonPrimitive?.content == "true"
        }
    } catch (_: Throwable) {
        null
    }

    override suspend fun setNickname(nick: String): NickStep = try {
        val response = client.patch(route.api("/api/v1/users/me/nickname")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("nickname", JsonPrimitive(nick)) }.toString())
        }
        when (response.status) {
            HttpStatusCode.OK -> NickStep.Taken
            HttpStatusCode.Conflict -> NickStep.Busy
            HttpStatusCode.BadRequest -> NickStep.OutOfBounds
            else -> NickStep.Offline
        }
    } catch (_: Throwable) {
        NickStep.Offline
    }
}
