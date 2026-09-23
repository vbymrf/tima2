package io.tima.core.network

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.tima.domain.chat.CallHistory
import io.tima.domain.chat.CallHistoryPage
import io.tima.domain.chat.CallRecord
import kotlinx.datetime.Instant

/**
 * Журнал звонков с сервера — `GET /api/v1/calls` (Ж1).
 *
 * **Строка приходит сырой, и разбирается сырой.** Ни «направления», ни «исхода» сервер не
 * считает, хотя знает, кто спрашивает: одна и та же запись читается по-разному у двоих, и
 * поле, зависящее от спросившего, нельзя ни кэшировать, ни сверить с `GET /calls/{id}`.
 * Складывает слова экран, по [CallRecord.outcome].
 *
 * **Времена — RFC3339, разбираются в миллисекунды тем же способом, что в `EscrowApi`.**
 * Отсутствующее поле остаётся нулём: `answered_at` нет — значит трубку не брали, и это
 * сведение, а не пробел.
 */
class CallHistoryOverHttp(
    private val route: ServerRoute,
    private val client: io.ktor.client.HttpClient,
    private val token: () -> String,
) : CallHistory {

    override suspend fun page(beforeMs: Long, limit: Int): CallHistoryPage? {
        val query = buildString {
            append("/api/v1/calls?limit=").append(limit)
            // Продолжение страницы — временем последней отданной строки. Сервер отбирает
            // строго, поэтому та же строка второй раз не придёт.
            if (beforeMs > 0) {
                append("&before=").append(Instant.fromEpochMilliseconds(beforeMs).toString())
            }
        }
        val response = try {
            client.get(route.api(query)) { header("Authorization", "Bearer ${token()}") }
        } catch (_: Throwable) {
            // До сервера не дошли. Не «звонков нет»: спутать эти два случая значит стереть
            // журнал при первом же походе в метро.
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        val rows = body["calls"]?.jsonArrayOrNull() ?: return null
        val records = rows.mapNotNull { element ->
            val o = element.jsonObjectOrNull() ?: return@mapNotNull null
            val callId = o.str("call_id").orEmpty()
            val createdAt = millisOrZero(o.str("created_at"))
            // Строка без идентификатора или без времени — не строка журнала. Пропускаем
            // её, а не всю страницу: одна испорченная запись не повод оставить человека
            // без журнала.
            if (callId.isEmpty() || createdAt == 0L) return@mapNotNull null
            CallRecord(
                callId = callId,
                video = o.str("kind") == "video",
                state = o.str("state").orEmpty(),
                initiatorId = o.str("initiator_id").orEmpty(),
                peerId = o.str("peer_id").orEmpty(),
                endedBy = o.str("ended_by").orEmpty(),
                createdAt = createdAt,
                answeredAt = millisOrZero(o.str("answered_at")),
                endedAt = millisOrZero(o.str("ended_at")),
            )
        }
        return CallHistoryPage(records, nextBeforeMs = millisOrZero(body.str("next_before")))
    }

    /** RFC3339 в миллисекунды. Поля нет или оно непонятно — ноль, то есть «не было». */
    private fun millisOrZero(stamp: String?): Long =
        if (stamp.isNullOrEmpty()) 0
        else runCatching { Instant.parse(stamp).toEpochMilliseconds() }.getOrDefault(0)
}
