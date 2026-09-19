package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.tima.core.call.CallDoor
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

    override suspend fun end(callId: String): Boolean = try {
        val response = client.post(route.api("/api/v1/calls/$callId/end")) {
            header("Authorization", "Bearer ${token()}")
        }
        response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent
    } catch (_: Throwable) {
        // Молча: итог звонка запишет сервер по вебхуку от SFU, а наша трубка уже положена.
        // Возвращать «не вышло» человеку здесь незачем — звонок для него кончился.
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
        return CallStep.Door(CallDoor(callId = callId, room = room, url = url, token = access))
    }
}
