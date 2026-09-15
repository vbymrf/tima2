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

    private fun HttpStatusCode.isSuccess() = value in 200..299
}
