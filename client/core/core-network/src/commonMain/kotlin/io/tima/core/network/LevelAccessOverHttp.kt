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
import io.tima.domain.chat.AccessGrant
import io.tima.domain.chat.AccessPort
import io.tima.domain.chat.AccessState
import io.tima.domain.chat.AskAccessStep
import io.tima.domain.chat.GrantStep
import io.tima.domain.chat.GrantsStep
import kotlinx.serialization.json.jsonArray

/**
 * Доступ к закрытым записям по HTTP (ADR-0019, ПЛАН-СОЦИУМА Г9).
 *
 * Одна ручка отвечает двоим по-разному: админу — весь состав, участнику — только своё.
 * Различает их сервер, а не мы: право знать, кто ещё просил доступ, — это право админа, и
 * решать его на клиенте значило бы спрашивать у него разрешения самому себе.
 */
class LevelAccessOverHttp(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) : AccessPort {

    override suspend fun ask(groupId: String): AskAccessStep {
        val response = try {
            client.post(route.api("/api/v1/groups/$groupId/level-requests")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        } catch (e: Throwable) {
            return AskAccessStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created ->
                AskAccessStep.Asked(AccessState.from(body?.str("state").orEmpty()))
            else -> AskAccessStep.Refused(body.codeOf())
        }
    }

    override suspend fun grants(groupId: String): GrantsStep {
        val response = try {
            client.get(route.api("/api/v1/groups/$groupId/level-grants")) {
                header("Authorization", "Bearer ${token()}")
            }
        } catch (e: Throwable) {
            return GrantsStep.Offline(classifyFailure(e).retryDelayMs)
        }
        val body = response.jsonBody()
        if (response.status != HttpStatusCode.OK) return GrantsStep.Refused(body.codeOf())

        // Участнику сервер отвечает своим кругом, админу — списком. Различие в ответе, а
        // не в запросе: клиент не обязан знать заранее, кто он в этой группе.
        body?.int("my_level")?.let { return GrantsStep.Mine(it) }

        val rows = runCatching { body?.get("grants")?.jsonArray }.getOrNull().orEmpty()
        return GrantsStep.Grants(
            rows.mapNotNull { element ->
                val row = element.jsonObjectOrNull() ?: return@mapNotNull null
                AccessGrant(
                    userId = row.str("user_id") ?: return@mapNotNull null,
                    level = row.int("level") ?: 3,
                    state = AccessState.from(row.str("state").orEmpty()),
                    untilEpoch = row.str("until_epoch").orEmpty(),
                )
            },
        )
    }

    override suspend fun decide(
        groupId: String,
        userId: String,
        grant: Boolean,
        untilEpoch: String,
    ): GrantStep {
        val response = try {
            client.put(route.api("/api/v1/groups/$groupId/level-grants/$userId")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("{\"grant\":$grant,\"until_epoch\":\"$untilEpoch\"}")
            }
        } catch (e: Throwable) {
            return GrantStep.Offline(classifyFailure(e).retryDelayMs)
        }
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.NoContent -> GrantStep.Done
            HttpStatusCode.Forbidden -> GrantStep.NotAllowed
            else -> GrantStep.Refused(response.jsonBody().codeOf())
        }
    }
}
