package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.PersonLocale
import io.tima.domain.chat.PersonLocales
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Страна и язык человека по HTTP (ПЛАН-ЯЗЫКА Я7).
 *
 * Хранятся в профиле на сервере, а не только на устройстве, потому что сервер ставит ими
 * штамп на запись при публикации: страна в телефоне не помогла бы ему отобрать выдачу.
 */
class PersonLocalesOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : PersonLocales {

    override suspend fun read(): PersonLocale? {
        val body = try {
            val response = client.get(route.api("/api/v1/users/me/locale")) {
                header("Authorization", "Bearer ${token()}")
            }
            if (response.status != HttpStatusCode.OK) return null
            response.jsonBody()
        } catch (e: Throwable) {
            return null
        } ?: return null
        return PersonLocale(
            lang = body.str("lang").orEmpty().ifEmpty { "ru" },
            country = body.str("country").orEmpty(),
        )
    }

    override suspend fun write(locale: PersonLocale): Boolean {
        val response = try {
            client.put(route.api("/api/v1/users/me/locale")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("lang", JsonPrimitive(locale.lang))
                        put("country", JsonPrimitive(locale.country))
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return false
        }
        return response.status == HttpStatusCode.OK
    }
}
