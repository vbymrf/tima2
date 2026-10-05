package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Копия ключей по модели Matrix (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ §3а): открытый ключ копии личности,
 * обёртки ключей личных сообщений и версий ключей групп.
 *
 * Сервер видит только шифртекст и открытый ключ с подписью — подпись проверяет клиент сам
 * (`KeyCopy.verify`), чтобы сервер не мог подсунуть свою пару.
 */
class KeyCopyApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    /** Открытый ключ копии своей личности. */
    class Key(
        val epoch: Int,
        val pub: ByteArray,
        val sig: ByteArray,
        /** Отключили своё устройство — пару пора сменить (М5). */
        val rotationDue: Boolean = false,
    )

    /** Что ответил сервер на вопрос о ключе копии. */
    sealed interface Current {
        data class Published(val key: Key) : Current
        data object Missing : Current
        data object Unknown : Current
    }

    /** `GET /users/me/key-copy`. */
    suspend fun current(): Current {
        val response = try {
            client.get(route.api("/api/v1/users/me/key-copy")) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return Current.Unknown
        }
        if (response.status == HttpStatusCode.NotFound) return Current.Missing
        if (response.status != HttpStatusCode.OK) return Current.Unknown
        val body = response.jsonBody() ?: return Current.Unknown
        val pub = body.str("pub")?.let { decodeBase64Url(it) }
        val sig = body.str("sig")?.let { decodeBase64Url(it) }
        val epoch = body.int("epoch") ?: return Current.Unknown
        if (pub == null || sig == null) return Current.Unknown
        return Current.Published(Key(epoch, pub, sig, rotationDue = body.bool("rotation_due") == true))
    }

    /** `PUT /users/me/key-copy` — опубликовать пару копии. `true` — принято или уже было. */
    suspend fun publish(key: Key): Boolean = try {
        client.put(route.api("/api/v1/users/me/key-copy")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody("""{"epoch":${key.epoch},"pub":"${encodeBase64Url(key.pub)}","sig":"${encodeBase64Url(key.sig)}"}""")
        }.status.value in 200..299
    } catch (e: Throwable) {
        false
    }

    /** Исход пополнения копии. */
    enum class Saved { OK, STALE, FAILED }

    /** `POST /chats/{id}/backup` — обёртки ключей сообщений в копию под эпоху [epoch]. */
    suspend fun saveMessages(chatId: String, epoch: Int, items: List<Pair<Long, ByteArray>>): Saved {
        if (items.isEmpty()) return Saved.OK
        val body = buildString {
            append("{\"epoch\":").append(epoch).append(",\"items\":[")
            items.forEachIndexed { i, (id, blob) ->
                if (i > 0) append(',')
                append("{\"message_id\":").append(id).append(",\"wrapped\":\"").append(encodeBase64Url(blob)).append("\"}")
            }
            append("]}")
        }
        return post("/api/v1/chats/$chatId/backup", body)
    }

    /** `POST /users/me/key-copy/groups` — версии ключей групп в копию. */
    suspend fun saveGroupKeys(epoch: Int, items: List<GroupItem>): Saved {
        if (items.isEmpty()) return Saved.OK
        val body = buildString {
            append("{\"epoch\":").append(epoch).append(",\"items\":[")
            items.forEachIndexed { i, it ->
                if (i > 0) append(',')
                append("{\"group_id\":\"").append(it.groupId).append("\",\"gk_version\":").append(it.gkVersion)
                append(",\"wrapped\":\"").append(encodeBase64Url(it.wrapped)).append("\"}")
            }
            append("]}")
        }
        return post("/api/v1/users/me/key-copy/groups", body)
    }

    private suspend fun post(path: String, body: String): Saved = try {
        val status = client.post(route.api(path)) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status
        when {
            status.value in 200..299 -> Saved.OK
            status == HttpStatusCode.Conflict -> Saved.STALE
            else -> Saved.FAILED
        }
    } catch (e: Throwable) {
        Saved.FAILED
    }

    /** Сообщение страницы копии: конверт с обёрткой для `key-copy` и её эфемерал. */
    class PageItem(val messageId: Long, val envelope: ByteArray, val wrapEphemeral: ByteArray)

    /** `GET /chats/{id}/backup` — страница копии, от новых к старым; `null` — не получена. */
    suspend fun page(chatId: String, before: Long, limit: Int = PAGE): List<PageItem>? {
        val path = "/api/v1/chats/$chatId/backup?limit=$limit" + if (before > 0) "&before=$before" else ""
        val response = try {
            client.get(route.api(path)) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val list = response.jsonBody()?.get("items")?.jsonArrayOrNull() ?: return null
        return list.mapNotNull { el ->
            val o = el.jsonObjectOrNull() ?: return@mapNotNull null
            PageItem(
                messageId = o.long("message_id") ?: return@mapNotNull null,
                envelope = o.str("envelope")?.let { decodeBase64Url(it) } ?: return@mapNotNull null,
                wrapEphemeral = o.str("wrap_ephemeral")?.let { decodeBase64Url(it) } ?: return@mapNotNull null,
            )
        }
    }

    /** Версия ключа группы в копии. */
    class GroupItem(val groupId: String, val gkVersion: Int, val wrapped: ByteArray)

    /** `GET /users/me/key-copy/groups`; `null` — не получено. */
    suspend fun groupKeys(): List<GroupItem>? {
        val response = try {
            client.get(route.api("/api/v1/users/me/key-copy/groups")) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val list = response.jsonBody()?.get("items")?.jsonArrayOrNull() ?: return null
        return list.mapNotNull { el ->
            val o = el.jsonObjectOrNull() ?: return@mapNotNull null
            GroupItem(
                groupId = o.str("group_id") ?: return@mapNotNull null,
                gkVersion = o.int("gk_version") ?: return@mapNotNull null,
                wrapped = o.str("wrapped")?.let { decodeBase64Url(it) } ?: return@mapNotNull null,
            )
        }
    }

    companion object {
        /** Страница копии — как у истории. */
        const val PAGE: Int = 100
    }
}
