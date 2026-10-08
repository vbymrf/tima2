package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Строка ленты состояний (ПЛАН-(ОП)-ОТМЕТОК-И-ПРИСУТСТВИЯ): последняя правда об одном.
 *
 * Времена `untilMs` — по часам сервера; пересчитываются в свои по `nowMs` страницы.
 */
sealed interface StateRow {
    val rev: Long

    /** Мои сообщения в переписке [chatId] доставлены и прочитаны собеседником [peerId] до времени написания. */
    data class Receipt(override val rev: Long, val chatId: String, val peerId: String, val deliveredMs: Long, val readMs: Long) : StateRow

    /** [fromId] печатает мне в [chatId] до [untilMs]; 0 — перестал. */
    data class Typing(override val rev: Long, val chatId: String, val fromId: String, val untilMs: Long) : StateRow

    /** [userId] в сети до [untilMs] или был(а) в [lastSeenMs]. */
    data class Presence(override val rev: Long, val userId: String, val online: Boolean, val untilMs: Long, val lastSeenMs: Long) : StateRow

    /**
     * Вершина сущности «зашли, забрали» (ПЛАН-(ОУ)): в открытой группе или канале новое до [topId];
     * [unread] — после моей отметки прочтения, не больше 100.
     */
    data class Top(override val rev: Long, val kind: String, val entityId: String, val topId: Long, val topAtMs: Long, val unread: Int) : StateRow

    /** «Отключить уведомления» у сущности (ПЛАН-(ОУ)): [off] — отключены. */
    data class Notify(override val rev: Long, val kind: String, val entityId: String, val off: Boolean) : StateRow
}

/** Страница ленты: вершина, строки после спрошенного номера и часы сервера. */
data class StatesPage(val rev: Long, val rows: List<StateRow>, val nowMs: Long)

/**
 * Лента состояний и «прочитано» — `GET /users/me/states`, `PUT /chats/{id}/read`.
 *
 * `null` — до сервера не дошли или он старый: ленты нет, и это не «ничего не изменилось».
 */
class StatesOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {
    suspend fun page(after: Long): StatesPage? = try {
        val response = client.get(route.api("/api/v1/users/me/states?after=$after")) {
            header("Authorization", "Bearer ${token()}")
        }
        if (response.status != HttpStatusCode.OK) {
            null
        } else {
            response.jsonBody()?.let { body ->
                val rows = body["states"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
                    val o = element.jsonObjectOrNull() ?: return@mapNotNull null
                    val rev = o.long("rev") ?: 0
                    when (o.str("kind")) {
                        "receipt" -> StateRow.Receipt(
                            rev, o.str("chat_id").orEmpty(), o.str("peer_id").orEmpty(),
                            o.long("delivered_ms") ?: 0, o.long("read_ms") ?: 0,
                        )
                        "typing" -> StateRow.Typing(rev, o.str("chat_id").orEmpty(), o.str("from_id").orEmpty(), o.long("until_ms") ?: 0)
                        "presence" -> StateRow.Presence(
                            rev, o.str("user_id").orEmpty(), o.bool("online") == true,
                            o.long("until_ms") ?: 0, o.long("last_seen_ms") ?: 0,
                        )
                        "top" -> StateRow.Top(
                            rev, o.str("entity_kind").orEmpty(), o.str("entity_id").orEmpty(),
                            o.long("top_id") ?: 0, o.long("top_at_ms") ?: 0, (o.long("unread") ?: 0).toInt(),
                        )
                        "notify" -> StateRow.Notify(rev, o.str("entity_kind").orEmpty(), o.str("entity_id").orEmpty(), o.bool("off") == true)
                        // Новый вид от сервера новее нас — пропускаем, ленту не роняем.
                        else -> null
                    }
                }
                StatesPage(rev = body.long("rev") ?: 0, rows = rows, nowMs = body.long("now_ms") ?: 0)
            }
        }
    } catch (e: Throwable) {
        null
    }

    /** Дочитал открытую группу или канал до [readId] (ПЛАН-(ОУ)). */
    suspend fun readEntity(kind: String, entityId: String, readId: Long): Boolean =
        putJson("/api/v1/users/me/reads", """{"kind":"$kind","entity_id":"$entityId","read_id":$readId}""")

    /** «Отключить уведомления» у сущности ([off]) или включить обратно (ПЛАН-(ОУ)). */
    suspend fun notify(kind: String, entityId: String, off: Boolean): Boolean =
        putJson("/api/v1/users/me/notify", """{"kind":"$kind","entity_id":"$entityId","off":$off}""")

    /** Устройство умеет «зашли, забрали»: открытые группы — вершиной, а не телом (ОУ4). */
    suspend fun declareTops(): Boolean = putJson("/api/v1/devices/me/delivery", """{"mode":"tops"}""")

    private suspend fun putJson(path: String, body: String): Boolean = try {
        client.put(route.api(path)) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.status == HttpStatusCode.NoContent
    } catch (e: Throwable) {
        false
    }

    /** Прочитал сообщения собеседника, написанные до [upToMs]. `true` — сервер принял. */
    suspend fun read(chatId: String, upToMs: Long): Boolean = try {
        client.put(route.api("/api/v1/chats/$chatId/read")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody("""{"up_to_ms":$upToMs}""")
        }.status == HttpStatusCode.NoContent
    } catch (e: Throwable) {
        false
    }
}
