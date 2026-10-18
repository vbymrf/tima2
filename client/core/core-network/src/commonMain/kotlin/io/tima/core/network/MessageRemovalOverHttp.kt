package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.tima.domain.chat.DeleteStep
import io.tima.domain.chat.RemoteDelete

/**
 * Удаление у всех (Решения §8): `DELETE /chats/{chat}/messages/{n}` и
 * `DELETE /groups/{group}/messages/{n}`. Получившие узнают событием `message.deleted`.
 */
class MessageRemovalOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : RemoteDelete {

    override suspend fun delete(chatId: String, serverId: Long, group: Boolean): DeleteStep {
        val path = if (group) "/api/v1/groups/$chatId/messages/$serverId" else "/api/v1/chats/$chatId/messages/$serverId"
        val response = try {
            client.delete(route.api(path)) { header("Authorization", "Bearer ${token()}") }
        } catch (e: Throwable) {
            return DeleteStep.Offline
        }
        if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.NoContent) return DeleteStep.Done
        // Тело читается один раз: второй раз поток ответа уже пуст.
        val code = response.jsonBody().codeOf()
        return if (response.status == HttpStatusCode.NotFound && code == "message_not_found") DeleteStep.Gone
        else DeleteStep.Refused(code)
    }
}
