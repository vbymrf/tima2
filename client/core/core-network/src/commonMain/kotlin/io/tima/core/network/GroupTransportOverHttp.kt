package io.tima.core.network

import io.tima.domain.chat.GroupSendStep
import io.tima.domain.chat.GroupTransport

/**
 * Отправка в группу по HTTP — переходник к порту домена.
 *
 * Тонкий намеренно: собирает сообщение `core-encryption`, решает, что делать с исходом,
 * домен, а здесь только перевод ответов сервера в исходы, которые домен различает.
 */
class GroupTransportOverHttp(private val api: GroupMessagesApi) : GroupTransport {

    override suspend fun send(
        groupId: String,
        clientMsgId: String,
        kind: Int,
        gkVersion: Int,
        payload: ByteArray,
        signature: ByteArray,
        createdAtUnixMs: Long,
    ): GroupSendStep {
        val ответ = api.send(
            groupId = groupId,
            clientMsgId = clientMsgId,
            kind = kind,
            gkVersion = gkVersion,
            payload = payload,
            signature = signature,
            createdAtUnixMs = createdAtUnixMs,
        )
        return when (ответ) {
            is SendGroupResult.Sent -> GroupSendStep.Sent(ответ.messageId)
            is SendGroupResult.Duplicate -> GroupSendStep.Duplicate(ответ.messageId)
            SendGroupResult.UnknownKeyVersion -> GroupSendStep.UnknownKeyVersion
            SendGroupResult.Banned -> GroupSendStep.Banned
            is SendGroupResult.SlowMode -> GroupSendStep.SlowMode(ответ.retryAfterSec)
            is SendGroupResult.NoConnection -> GroupSendStep.Offline(ответ.link.retryDelayMs)
            is SendGroupResult.Refused -> GroupSendStep.Refused(ответ.code)
        }
    }
}
