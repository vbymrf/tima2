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
import io.tima.core.call.Calls
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
        )
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
        return CallStep.Door(CallDoor(callId = callId, room = room, url = url, token = access))
    }
}
