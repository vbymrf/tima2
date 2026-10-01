package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.core.call.CallDoor
import io.tima.core.call.CallSnapshot
import io.tima.core.call.CallStep
import io.tima.core.call.CallUpdate
import io.tima.core.call.CallUpdates
import io.tima.core.call.Calls
import io.tima.core.call.GroupCallInfo
import io.tima.core.call.GroupCallLive
import io.tima.core.call.GroupCallMember
import io.tima.core.call.GroupControl
import io.tima.core.call.GroupRoom
import io.tima.core.call.GroupRules
import io.tima.core.call.VideoCeiling
import io.tima.core.call.VideoTier
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Сигналинг звонка по HTTP — `/api/v1/calls` (ПЛАН-ЗВОНКОВ.md §0).
 *
 * **Ручки на сервере были задолго до клиента**: `POST /calls`, `/calls/{id}/answer`,
 * `/calls/{id}/end`. Первое приложение их звало, второе до сегодняшнего дня — нет; поэтому
 * они и не были проверены ничем.
 *
 * **Адрес SFU приходит с сервера, а не зашит в клиент.** Он зависит от развёртывания
 * (`LIVEKIT_URL` в окружении бэкенда), и зашивать его сюда значило бы ломать клиент при
 * переезде стенда.
 *
 * **`503 no_livekit` — отдельный случай.** Сервер отвечает им, когда у него нет ключей
 * LiveKit. Это не «не получилось», а «звонков у нас нет вовсе», и человеку надо сказать
 * именно так, а не «попробуйте позже».
 */
class CallsOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : Calls {

    override suspend fun start(peerId: String, video: Boolean): CallStep {
        val response = try {
            client.post(route.api("/api/v1/calls")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("peer_id", JsonPrimitive(peerId))
                        put("kind", JsonPrimitive(if (video) "video" else "audio"))
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return CallStep.Offline(classifyFailure(e).retryDelayMs)
        }
        return doorOf(response, needCallId = true)
    }

    override suspend fun answer(callId: String): CallStep {
        val response = try {
            client.post(route.api("/api/v1/calls/$callId/answer")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return CallStep.Offline(classifyFailure(e).retryDelayMs)
        }
        // Ответ на `/answer` идентификатора звонка не повторяет — он известен спросившему.
        return doorOf(response, needCallId = false, knownCallId = callId)
    }

    /** Свежий токен той же комнаты — для перезахода (стенд, смена набора на ходу). */
    override suspend fun join(callId: String): CallStep {
        val response = try {
            client.post(route.api("/api/v1/calls/$callId/join")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return CallStep.Offline(classifyFailure(e).retryDelayMs)
        }
        // Как и `/answer`, ответ идентификатора звонка не повторяет — он известен.
        return doorOf(response, needCallId = false, knownCallId = callId)
    }

    override suspend fun end(callId: String, busy: Boolean): Boolean = try {
        // Причина уходит в запросе, а не в теле: ручка старая, тело у неё пустое, и
        // заводить его ради одного слова значило бы менять формат там, где хватает
        // параметра. Сервер принимает только `busy` и только на звонящем звонке.
        val where = "/api/v1/calls/$callId/end" + if (busy) "?reason=busy" else ""
        val response = client.post(route.api(where)) {
            header("Authorization", "Bearer ${token()}")
        }
        response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent
    } catch (_: Throwable) {
        // Молча: итог звонка запишет сервер по вебхуку от SFU, а наша трубка уже положена.
        // Возвращать «не вышло» человеку здесь незачем — звонок для него кончился.
        false
    }

    /**
     * Снимок звонка — `GET /calls/{id}` (П1).
     *
     * **`404` означает «кончился», и это не натяжка.** Строку убрал сборщик мусора либо
     * её не было вовсе; для того, кто спрашивает «звонить ли телефону», оба ответа
     * одинаковы. А вот отказ сети — другое: там мы **не знаем**, и `null` говорит
     * именно это.
     */
    override suspend fun snapshot(callId: String): CallSnapshot? {
        val response = try {
            client.get(route.api("/api/v1/calls/$callId")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (_: Throwable) {
            return null
        }
        if (response.status == HttpStatusCode.NotFound) {
            return CallSnapshot(callId, state = "ended", video = false, initiatorId = "", peerId = "")
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        return CallSnapshot(
            callId = body.str("call_id") ?: callId,
            state = body.str("state").orEmpty(),
            video = body.str("kind") == "video",
            initiatorId = body.str("initiator_id").orEmpty(),
            peerId = body.str("peer_id").orEmpty(),
            groupId = body.str("group_id").orEmpty(),
        )
    }

    override suspend fun updates(after: Long): CallUpdates? {
        val response = try {
            client.get(route.api("/api/v1/calls/updates?after=$after")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (_: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        val rows = body["updates"]?.jsonArrayOrNull() ?: return null
        val updates = rows.mapNotNull { element ->
            val o = element.jsonObjectOrNull() ?: return@mapNotNull null
            val cts = o.str("cts")?.toLongOrNull() ?: return@mapNotNull null
            val callId = o.str("call_id").orEmpty()
            val call = o["call"]?.jsonObjectOrNull()
            if (callId.isEmpty() || call == null) return@mapNotNull null
            CallUpdate(
                cts = cts,
                callId = callId,
                change = o.str("change").orEmpty(),
                here = o.bool("here") == true,
                call = CallSnapshot(
                    callId = callId,
                    state = call.str("state").orEmpty(),
                    video = call.str("kind") == "video",
                    initiatorId = call.str("initiator_id").orEmpty(),
                    peerId = call.str("peer_id").orEmpty(),
                    groupId = call.str("group_id").orEmpty(),
                ),
                atMs = o.str("at")
                    ?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() }
                    ?: 0,
            )
        }
        return CallUpdates(
            top = body.str("top")?.toLongOrNull() ?: 0,
            gap = body.bool("gap") == true,
            more = body.bool("more") == true,
            updates = updates,
        )
    }

    override suspend fun seen(callIds: List<String>): Boolean = try {
        client.post(route.api("/api/v1/calls/seen")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("call_ids", kotlinx.serialization.json.JsonArray(callIds.map { JsonPrimitive(it) }))
                }.toString(),
            )
        }.status == HttpStatusCode.OK
    } catch (_: Throwable) {
        false
    }

    private suspend fun doorOf(
        response: io.ktor.client.statement.HttpResponse,
        needCallId: Boolean,
        knownCallId: String = "",
    ): CallStep {
        if (response.status == HttpStatusCode.ServiceUnavailable) return CallStep.NotConfigured
        val body = response.jsonBody()
        val ok = response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Created
        if (!ok) return CallStep.Refused(body.codeOf())

        val room = body?.str("room").orEmpty()
        val url = body?.str("url").orEmpty()
        val access = body?.str("token").orEmpty()
        val callId = if (needCallId) body?.str("call_id").orEmpty() else knownCallId
        // Пустое поле — тот же отказ, только тихий: без адреса или токена в комнату не
        // войти, и делать вид, что дверь открыта, хуже честного отказа.
        if (room.isEmpty() || url.isEmpty() || access.isEmpty() || callId.isEmpty()) {
            return CallStep.Refused("ответ без двери")
        }
        return CallStep.Door(
            CallDoor(callId = callId, room = room, url = url, token = access, video = ceilingOf(body), group = groupOf(body)),
        )
    }

    /** Групповой звонок в двери (ПЛАН-ГРУППОВЫХ-ЗВОНКОВ ГЗ2); у звонка на двоих — `null`. */
    private fun groupOf(body: JsonObject?): GroupRoom? {
        if (body?.str("type") != "group") return null
        val groupId = body.str("group_id").orEmpty().ifEmpty { return null }
        return GroupRoom(
            groupId = groupId,
            creatorId = body.str("creator_id").orEmpty(),
            paused = body.bool("paused") == true,
            rules = rulesOf(body["rules"] as? JsonObject),
            micForbidden = (body["forbidden"] as? JsonObject)?.bool("mic") == true,
            videoForbidden = (body["forbidden"] as? JsonObject)?.bool("video") == true,
        )
    }

    /** Правила группового звонка; нет поля — умолчание приложения, те же числа (решение 4). */
    private fun rulesOf(rules: JsonObject?): GroupRules {
        rules ?: return GroupRules()
        val tiers = rules["video"]?.jsonArrayOrNull()?.mapNotNull { e ->
            val o = e.jsonObjectOrNull() ?: return@mapNotNull null
            VideoTier(upTo = o.int("up_to") ?: return@mapNotNull null, height = o.int("height") ?: return@mapNotNull null)
        }
        return GroupRules(
            max = rules.int("max")?.takeIf { it > 1 } ?: GroupRules().max,
            tiers = tiers?.takeIf { it.isNotEmpty() } ?: GroupRules().tiers,
        )
    }

    override suspend fun startGroup(groupId: String, ring: Boolean, video: Boolean, invited: List<String>): CallStep {
        val response = try {
            client.post(route.api("/api/v1/groups/$groupId/call")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("ring", JsonPrimitive(ring))
                        put("video", JsonPrimitive(video))
                        put("invited", kotlinx.serialization.json.JsonArray(invited.map { JsonPrimitive(it) }))
                    }.toString(),
                )
            }
        } catch (e: Throwable) {
            return CallStep.Offline(classifyFailure(e).retryDelayMs)
        }
        return doorOf(response, needCallId = true)
    }

    override suspend fun groupCall(groupId: String): GroupCallInfo? {
        val response = try {
            client.get(route.api("/api/v1/groups/$groupId/call")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (_: Throwable) {
            return null
        }
        if (response.status != HttpStatusCode.OK) return null
        val body = response.jsonBody() ?: return null
        val call = (body["call"] as? JsonObject)?.let { c ->
            GroupCallLive(
                callId = c.str("call_id").orEmpty(),
                creatorId = c.str("creator_id").orEmpty(),
                video = c.str("kind") == "video",
                startedAtMs = msOf(c.str("started_at")),
                paused = c.bool("paused") == true,
                members = c["participants"]?.jsonArrayOrNull()?.mapNotNull { e ->
                    val o = e.jsonObjectOrNull() ?: return@mapNotNull null
                    GroupCallMember(
                        userId = o.str("user_id") ?: return@mapNotNull null,
                        state = o.str("state").orEmpty(),
                        invited = o.bool("invited") == true,
                        removed = o.bool("removed") == true,
                        micForbidden = o.bool("mic_forbidden") == true,
                        videoForbidden = o.bool("video_forbidden") == true,
                    )
                }.orEmpty(),
            )
        }?.takeIf { it.callId.isNotEmpty() }
        return GroupCallInfo(
            call = call,
            canStart = body.bool("can_start") == true,
            myRole = body.str("my_role").orEmpty(),
            rules = rulesOf(body["rules"] as? JsonObject),
            ttlUntilMs = body.str("call_ttl_until")?.let { msOf(it) }?.takeIf { it > 0 },
        )
    }

    override suspend fun control(callId: String, action: GroupControl, userId: String): Boolean = try {
        client.post(route.api("/api/v1/calls/$callId/control")) {
            header("Authorization", "Bearer ${token()}")
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("action", JsonPrimitive(action.wire))
                    if (userId.isNotEmpty()) put("user_id", JsonPrimitive(userId))
                }.toString(),
            )
        }.status == HttpStatusCode.OK
    } catch (_: Throwable) {
        false
    }

    private fun msOf(text: String?): Long =
        text?.let { runCatching { kotlinx.datetime.Instant.parse(it).toEpochMilliseconds() }.getOrNull() } ?: 0

    /**
     * Потолок видео от сервера (ПЛАН-ВИДЕО.md В5б). Нет поля или оно неполное — `null`, и
     * звонок берёт умолчание приложения: половина потолка хуже умолчания целиком.
     */
    private fun ceilingOf(body: JsonObject?): VideoCeiling? {
        val video = body?.get("video") as? JsonObject ?: return null
        return VideoCeiling(
            width = video.int("width")?.takeIf { it > 0 } ?: return null,
            height = video.int("height")?.takeIf { it > 0 } ?: return null,
            fps = video.int("fps")?.takeIf { it > 0 } ?: return null,
            bitrate = video.int("bitrate")?.takeIf { it > 0 } ?: return null,
        )
    }
}
