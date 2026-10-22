package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.core.media.Media
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Медиа-хранилище по HTTP — media-storage.md §2: файл не ходит через наш сервер.
 *
 * Три шага: `POST /media/init` (сервер заводит объект и даёт подписанную ссылку в
 * MinIO), `PUT` по этой ссылке — уже без нашего токена, — и `POST /media/complete`,
 * которым сервер сверяет размер и переводит объект в готовые. Пропустить третий шаг
 * нельзя: незавершённый объект ни во что не ставится, аватар с ним сервер отвергнет.
 *
 * **Один кусок, не чанки.** Аватар — 512×512 JPEG, десятки килобайт; резать на части
 * есть смысл от мегабайт. Появится большое медиа — появится и нарезка, здесь же.
 *
 * **`is_encrypted = false`.** Аватар — публичная картинка: её видит любой, с кем
 * человек переписывается. Шифровать публичное значило бы раздавать ключ всем подряд.
 */
class MediaOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : Media {

    override suspend fun uploadSealed(sealed: ByteArray, mime: String, postmanChat: String?, postmanPeer: String?): String? = try {
        val init = client.post(route.api("/api/v1/media/init")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("size_bytes", JsonPrimitive(sealed.size))
                    put("mime", JsonPrimitive(mime))
                    put("is_encrypted", JsonPrimitive(true))
                    if (postmanChat != null && postmanPeer != null) {
                        put("postman", buildJsonObject {
                            put("chat_id", JsonPrimitive(postmanChat))
                            put("peer_id", JsonPrimitive(postmanPeer))
                        })
                    }
                }.toString(),
            )
        }
        if (init.status != HttpStatusCode.Created) {
            null
        } else {
            val body = Json.parseToJsonElement(init.bodyAsText()).jsonObject
            val mediaId = body["media_id"]?.jsonPrimitive?.content
            val url = body["upload_urls"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
            if (mediaId == null || url == null) {
                null
            } else {
                // Байты закрыты ключом файла — тип для хранилища один: бинарный.
                val put = client.put(url) {
                    contentType(ContentType.Application.OctetStream)
                    setBody(sealed)
                }
                if (!put.status.isSuccess()) {
                    null
                } else {
                    val done = client.post(route.api("/api/v1/media/complete")) {
                        header("Authorization", "Bearer ${token()}")
                        contentType(ContentType.Application.Json)
                        setBody(buildJsonObject { put("media_id", JsonPrimitive(mediaId)) }.toString())
                    }
                    if (done.status == HttpStatusCode.OK) mediaId else null
                }
            }
        }
    } catch (_: Throwable) {
        null
    }

    override suspend fun upload(bytes: ByteArray, mime: String): String? = try {
        val init = client.post(route.api("/api/v1/media/init")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("size_bytes", JsonPrimitive(bytes.size))
                    put("mime", JsonPrimitive(mime))
                    put("is_encrypted", JsonPrimitive(false))
                }.toString(),
            )
        }
        if (init.status != HttpStatusCode.Created && init.status != HttpStatusCode.OK) return null
        val body = Json.parseToJsonElement(init.bodyAsText()).jsonObject
        val mediaId = body["media_id"]?.jsonPrimitive?.content ?: return null
        // Дедупликация: тот же файл уже лежит — заливать нечего, объект готов.
        if (body["dedup"]?.jsonPrimitive?.content == "true") return mediaId

        val url = body["upload_urls"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: return null
        val put = client.put(url) {
            contentType(ContentType.parse(mime))
            setBody(bytes)
        }
        if (!put.status.isSuccess()) return null

        val done = client.post(route.api("/api/v1/media/complete")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("media_id", JsonPrimitive(mediaId)) }.toString())
        }
        if (done.status == HttpStatusCode.OK) mediaId else null
    } catch (_: Throwable) {
        null
    }

    override suspend fun download(mediaId: String): ByteArray? = try {
        val meta = client.get(route.api("/api/v1/media/$mediaId/url")) {
            header("Authorization", "Bearer ${token()}")
        }
        if (meta.status != HttpStatusCode.OK) return null
        val url = Json.parseToJsonElement(meta.bodyAsText()).jsonObject["urls"]
            ?.jsonArray?.firstOrNull()?.jsonPrimitive?.content ?: return null
        val file = client.get(url)
        if (file.status.isSuccess()) file.readRawBytes() else null
    } catch (_: Throwable) {
        null
    }

    override suspend fun fetch(mediaId: String): Media.MediaFetch = try {
        val meta = client.get(route.api("/api/v1/media/$mediaId/url")) {
            header("Authorization", "Bearer ${token()}")
        }
        when {
            // Срок почтальона прошёл (Решения §6): попросить снова — `requestAgain`.
            meta.status == HttpStatusCode.Gone -> Media.MediaFetch.Gone
            meta.status != HttpStatusCode.OK -> Media.MediaFetch.Failed
            else -> {
                val url = Json.parseToJsonElement(meta.bodyAsText()).jsonObject["urls"]
                    ?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
                val file = url?.let { client.get(it) }
                if (file != null && file.status.isSuccess()) Media.MediaFetch.Got(file.readRawBytes()) else Media.MediaFetch.Failed
            }
        }
    } catch (_: Throwable) {
        Media.MediaFetch.Failed
    }

    override suspend fun requestAgain(mediaId: String): Boolean = try {
        client.post(route.api("/api/v1/media/$mediaId/request")) {
            header("Authorization", "Bearer ${token()}")
        }.status == HttpStatusCode.Accepted
    } catch (_: Throwable) {
        false
    }

    override suspend fun reupload(mediaId: String, sealed: ByteArray): Boolean = try {
        val reopen = client.post(route.api("/api/v1/media/$mediaId/reupload")) {
            header("Authorization", "Bearer ${token()}")
        }
        val url = if (reopen.status == HttpStatusCode.OK) {
            Json.parseToJsonElement(reopen.bodyAsText()).jsonObject["upload_urls"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content
        } else null
        if (url == null) {
            false
        } else {
            val put = client.put(url) {
                contentType(ContentType.Application.OctetStream)
                setBody(sealed)
            }
            put.status.isSuccess() && client.post(route.api("/api/v1/media/complete")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { put("media_id", JsonPrimitive(mediaId)) }.toString())
            }.status == HttpStatusCode.OK
        }
    } catch (_: Throwable) {
        false
    }

    override suspend fun rules(): Map<String, String>? = try {
        val resp = client.get(route.api("/api/v1/media/rules")) {
            header("Authorization", "Bearer ${token()}")
        }
        if (resp.status != HttpStatusCode.OK) null
        else Json.parseToJsonElement(resp.bodyAsText()).jsonObject.mapValues { (_, v) ->
            // Список запретов — через запятую: разбор правил выше не знает JSON.
            runCatching { v.jsonArray.joinToString(",") { it.jsonPrimitive.content } }.getOrElse { v.jsonPrimitive.content }
        }
    } catch (_: Throwable) {
        null
    }

    override suspend fun drop(mediaId: String): Boolean = try {
        client.post(route.api("/api/v1/media/$mediaId/drop")) {
            header("Authorization", "Bearer ${token()}")
        }.status == HttpStatusCode.NoContent
    } catch (_: Throwable) {
        false
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299
}
