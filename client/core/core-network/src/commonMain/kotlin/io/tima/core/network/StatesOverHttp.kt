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
