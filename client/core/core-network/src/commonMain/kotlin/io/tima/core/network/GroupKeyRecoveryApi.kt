package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Запрос недостающих версий группового ключа (ADR-0010 §этап 1).
 *
 * **Какие версии недостают, решает сервер, а не мы.** Он знает и историю ротаций, и то,
 * какие обёртки выданы этому устройству; наш список был бы вторым мнением, которое
 * разойдётся с первым.
 *
 * **Просят не у админа, а у участников.** Сервер находит устройства, у которых нужные
 * версии есть, и рассылает им `recovery.gk_request`. Согласие в группе автоматическое:
 * участник, имеющий право на историю, получает её от того, у кого она есть.
 *
 * **Подпись ключом личности обязательна, если у аккаунта заведена секретная фраза.** Это
 * заслон против угона номера: укравший SIM получает device JWT, но без фразы не подпишет
 * запрос и историю не получит. Ключ личности на устройстве не хранится — он выводится из
 * двенадцати слов, — поэтому запрос без подписи законен и штатен, а отказ по подписи
 * означает «нужна фраза», а не поломку.
 */
class GroupKeyRecoveryApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    /**
     * `POST /api/v1/groups/{id}/keys/recover`.
     *
     * @param signature подпись ключом личности над `tima.recover.v1|group|device`,
     *   base64url. `null` — у аккаунта нет фразы либо она сейчас недоступна.
     */
    suspend fun request(groupId: String, signature: String? = null): RecoverResult {
        val тело = if (signature == null) "{}" else """{"signature":"$signature"}"""
        val response = try {
            client.post(route.api("/api/v1/groups/$groupId/keys/recover")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(тело)
            }
        } catch (e: Throwable) {
            return RecoverResult.NoConnection(classifyFailure(e))
        }
        val body = response.jsonBody()
        if (response.status == HttpStatusCode.OK) {
            return RecoverResult.Requested(
                versions = body?.int("requested") ?: 0,
                helpers = body?.int("helpers") ?: 0,
            )
        }
        return when (body.codeOf()) {
            // Не отказ по существу: аккаунт защищён фразой, и её надо ввести.
            "bad_identity_sig" -> RecoverResult.NeedsSecretPhrase
            "not_member" -> RecoverResult.NotMember
            else -> RecoverResult.Refused(response.status.value, body.codeOf())
        }
    }
}

sealed interface RecoverResult {
    /**
     * @param versions сколько версий запрошено. Ноль означает, что недостающих нет —
     *   то есть ключи уже в пути или сообщение не читается по другой причине.
     * @param helpers скольким устройствам ушла просьба. Ноль — просить некого: ни у кого
     *   из участников этих версий нет.
     */
    data class Requested(val versions: Int, val helpers: Int) : RecoverResult

    /** У аккаунта заведена секретная фраза, и без подписи ею историю не отдадут. */
    data object NeedsSecretPhrase : RecoverResult

    data object NotMember : RecoverResult
    data class Refused(val status: Int, val code: String) : RecoverResult
    data class NoConnection(val link: LinkState) : RecoverResult
}
