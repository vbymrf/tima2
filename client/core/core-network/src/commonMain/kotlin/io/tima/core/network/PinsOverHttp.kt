package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.domain.chat.PinStep
import io.tima.domain.chat.RemotePins
import io.tima.domain.chat.ShowcaseOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Закреплённое (ПЛАН-(ЗО) ЗО1, ЗО3): `GET/PUT/DELETE /chats/{id}/pin` и `/groups/{id}/pin`.
 * Получившие узнают событием `message.pinned`.
 */
class PinsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : RemotePins {

    private fun path(chatId: String, group: Boolean) =
        if (group) "/api/v1/groups/$chatId/pin" else "/api/v1/chats/$chatId/pin"

    override suspend fun read(chatId: String, group: Boolean): Long? {
        val response = try {
            client.get(route.api(path(chatId, group))) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        return response.jsonBody()?.long("message_id")
    }

    override suspend fun set(chatId: String, group: Boolean, serverId: Long): PinStep = step {
        client.put(route.api(path(chatId, group))) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody("{\"message_id\":$serverId}")
        }
    }

    override suspend fun clear(chatId: String, group: Boolean): PinStep = step {
        client.delete(route.api(path(chatId, group))) { header("Authorization", "Bearer ${token()}") }
    }

    private fun listPath(chatId: String, group: Boolean) =
        if (group) "/api/v1/groups/$chatId/pins" else "/api/v1/chats/$chatId/pins"

    /** Список (СЦ12); старый сервер (404) — из одного закреплённого. */
    override suspend fun list(chatId: String, group: Boolean): io.tima.domain.chat.PinBoard? {
        val response = try {
            client.get(route.api(listPath(chatId, group))) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status == HttpStatusCode.NotFound) {
            return read(chatId, group)?.let { io.tima.domain.chat.PinBoard("", if (it > 0) listOf(it) else emptyList()) }
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        val ids = body["items"]?.jsonArrayOrNull()?.mapNotNull { it.jsonObjectOrNull()?.long("message_id") }.orEmpty()
        return io.tima.domain.chat.PinBoard(body.str("title").orEmpty(), ids)
    }

    override suspend fun add(chatId: String, group: Boolean, ids: List<Long>, replace: Boolean): PinStep = step {
        client.post(route.api(listPath(chatId, group))) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody("{\"message_ids\":[${ids.joinToString(",")}],\"mode\":\"${if (replace) "replace" else "add"}\"}")
        }
    }

    override suspend fun remove(chatId: String, group: Boolean, ids: List<Long>): PinStep = step {
        client.delete(route.api(listPath(chatId, group))) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody("{\"message_ids\":[${ids.joinToString(",")}]}")
        }
    }

    override suspend fun rename(chatId: String, group: Boolean, title: String): PinStep = step {
        client.put(route.api(listPath(chatId, group) + "/title")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.buildJsonObject { put("title", kotlinx.serialization.json.JsonPrimitive(title)) }.toString())
        }
    }

    private suspend fun step(call: suspend () -> HttpResponse): PinStep {
        val response = try {
            call()
        } catch (e: Throwable) {
            return PinStep.Offline
        }
        return if (response.status == HttpStatusCode.OK) PinStep.Done else PinStep.Refused(response.jsonBody().codeOf())
    }
}

/**
 * Описание (ПЛАН-(ЗО) ЗО2, ЗО4): `GET /{groups|channels|communities}/{id}/showcase` и суперсообщение
 * `PUT/DELETE …/showcase/super`. Сообщения группы приходят кадрами — тем же JSON, что история, и
 * открываются тем же разбором с проверкой подписи; у канала и сообщества — открытые узлы и разметка.
 */
class ShowcaseOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    /** Выдача как есть: суперсообщение и сырые сообщения. `null` — сеть или отказ. */
    class Raw(
        val superId: Long,
        /** У группы — кадр для разбора (`GroupFrame.toStored`); у остальных — `null`. */
        val frames: List<ByteArray>,
        /** У канала и сообщества: номер, автор, узлы, разметка. */
        val open: List<OpenItem>,
    )

    class OpenItem(val id: Long, val authorId: String?, val nodes: List<String>, val markup: String)

    private fun base(of: ShowcaseOf) = when (of.kind) {
        "channel" -> "/api/v1/channels/${of.id}"
        "community" -> "/api/v1/communities/${of.id}"
        else -> "/api/v1/groups/${of.id}"
    }

    suspend fun page(of: ShowcaseOf): Raw? {
        val response = try {
            client.get(route.api(base(of) + "/showcase")) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        val superId = body.long("super") ?: 0L
        if (of.kind == "group") {
            val frames = body["messages"]?.jsonArrayOrNull().orEmpty().mapNotNull { el ->
                el.jsonObjectOrNull()?.let { GroupFrame.toStored(it.toString()) }
            }
            return Raw(superId, frames, emptyList())
        }
        val list = body[if (of.kind == "channel") "posts" else "messages"]?.jsonArrayOrNull().orEmpty()
        val open = list.mapNotNull { el ->
            val o = el.jsonObjectOrNull() ?: return@mapNotNull null
            val id = o.long("post_id") ?: o.long("message_id") ?: return@mapNotNull null
            val nodes = o["nodes"]?.jsonArrayOrNull().orEmpty().mapNotNull { n -> runCatching { (n as JsonPrimitive).content }.getOrNull() }
            // Разметка приходит объектом JSON (сервер кладёт её как есть); строкой — то же самое.
            val markup = o["markup"]?.let { m -> (m as? JsonPrimitive)?.content ?: m.toString() }.orEmpty()
            OpenItem(id, o.str("author_id"), nodes, markup)
        }
        return Raw(superId, emptyList(), open)
    }

    /**
     * Новое сообщение описания сообщества — открытыми узлами и разметкой (суперсообщение из
     * редактора, ЗО5). Номер сообщения; `null` — сеть или отказ (`showcase_full` — уже 50).
     */
    suspend fun addCommunity(communityId: String, body: io.tima.domain.chat.OpenBody): Long? {
        val response = try {
            client.post(route.api("/api/v1/communities/$communityId/messages")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("text", JsonPrimitive(body.text.ifBlank { " " }))
                        put("nodes", JsonArray(body.nodes.map(::JsonPrimitive)))
                        if (body.markup.isNotEmpty()) put("markup", JsonPrimitive(body.markup))
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.Created) return null
        return response.jsonBody()?.long("message_id")
    }

    suspend fun setSuper(of: ShowcaseOf, serverId: Long): Boolean {
        val url = route.api(base(of) + "/showcase/super")
        val response = try {
            if (serverId > 0) {
                client.put(url) {
                    header("Authorization", "Bearer ${token()}")
                    contentType(ContentType.Application.Json)
                    setBody("{\"message_id\":$serverId}")
                }
            } else {
                client.delete(url) { header("Authorization", "Bearer ${token()}") }
            }
        } catch (e: Throwable) {
            return false
        }
        return response.status == HttpStatusCode.OK
    }
}
