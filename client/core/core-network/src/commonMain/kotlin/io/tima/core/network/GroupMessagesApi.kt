package io.tima.core.network

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Отправка сообщения в группу (`POST /groups/{id}/messages`).
 *
 * **Конверта здесь нет, и это не упрощение, а другой протокол.** У личного сообщения на
 * провод уходит `Envelope` с обёртками ключа на каждое устройство и своим escrow; у
 * группового — три поля: `payload`, подпись и метаданные. Ключ у участников уже есть, а
 * escrow один на версию ключа, а не на сообщение (`crypto-protocol §4.1`).
 *
 * **`gk_version` обязателен для частной группы.** Он едет открытым, потому что получатель
 * должен знать, каким ключом расшифровывать, ещё до расшифровки. Нулевая версия означает
 * публичную группу с открытым текстом — сюда её пускать нельзя, и не пускает фасад
 * `GroupMessages`.
 */
class GroupMessagesApi(
    private val route: ServerRoute,
    private val client: HttpClient,
    private val token: () -> String,
) {

    /**
     * @param clientMsgId ключ идемпотентности. **Тот же на каждом повторе**: сервер по
     *   нему опознаёт дубль и отвечает `duplicate`, а не заводит второе сообщение. Без
     *   него обрыв связи после доставки давал бы два сообщения в переписке.
     */
    suspend fun send(
        groupId: String,
        clientMsgId: String,
        kind: Int,
        gkVersion: Int,
        payload: ByteArray,
        signature: ByteArray,
        createdAtUnixMs: Long,
        threadRoot: Long = 0,
        replyTo: Long = 0,
    ): SendGroupResult {
        val requestBody = "{\"client_msg_id\":\"" + clientMsgId + "\"" +
            ",\"kind\":" + kind +
            ",\"gk_version\":" + gkVersion +
            ",\"payload\":\"" + encodeBase64Url(payload) + "\"" +
            ",\"thread_root\":" + threadRoot +
            ",\"reply_to\":" + replyTo +
            ",\"created_at_unix_ms\":" + createdAtUnixMs +
            ",\"signature\":\"" + encodeBase64Url(signature) + "\"}"

        val response = try {
            client.post(route.api("/api/v1/groups/$groupId/messages")) {
                header("Authorization", "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        } catch (e: Throwable) {
            return SendGroupResult.NoConnection(classifyFailure(e))
        }

        val body = response.jsonBody()
        return when {
            response.status == HttpStatusCode.Created ->
                SendGroupResult.Sent(messageId = body?.long("message_id") ?: 0)

            // Повтор той же отправки. Для очереди это успех, а не отказ: сообщение у
            // сервера есть, и слать его в третий раз незачем.
            response.status == HttpStatusCode.OK && body?.get("duplicate") != null ->
                SendGroupResult.Duplicate(messageId = body.long("message_id") ?: 0)

            // Версия ключа, которой сервер не знает: наш ключ новее серверного состояния
            // либо мы ошиблись версией. Пересобирать сообщение бессмысленно, пока не
            // разберёмся с ключами.
            body.codeOf() == "unknown_gk_version" -> SendGroupResult.UnknownKeyVersion

            // Slow mode и бан — не наша беда и не повод для повтора «поскорее».
            response.status == HttpStatusCode.TooManyRequests ->
                SendGroupResult.SlowMode(retryAfterSec = body?.int("retry_after") ?: 0)

            body.codeOf() == "banned" -> SendGroupResult.Banned
            else -> SendGroupResult.Refused(response.status.value, body.codeOf())
        }
    }
}

sealed interface SendGroupResult {
    data class Sent(val messageId: Long) : SendGroupResult

    /** Сервер уже принимал это сообщение по тому же `client_msg_id`. */
    data class Duplicate(val messageId: Long) : SendGroupResult

    /** Такой версии GK у группы нет: сначала разобраться с ключами, потом слать. */
    data object UnknownKeyVersion : SendGroupResult

    /** Медленный режим группы: подождать названное число секунд. */
    data class SlowMode(val retryAfterSec: Int) : SendGroupResult

    /** Участник заблокирован: писать нельзя, и повтор этого не изменит. */
    data object Banned : SendGroupResult

    data class Refused(val status: Int, val code: String) : SendGroupResult
    data class NoConnection(val link: LinkState) : SendGroupResult
}
