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
        level: Int,
    ): GroupSendStep {
        val answer = api.send(
            groupId = groupId,
            clientMsgId = clientMsgId,
            kind = kind,
            gkVersion = gkVersion,
            payload = payload,
            signature = signature,
            createdAtUnixMs = createdAtUnixMs,
            level = level,
        )
        return when (answer) {
            is SendGroupResult.Sent -> GroupSendStep.Sent(answer.messageId)
            is SendGroupResult.Duplicate -> GroupSendStep.Duplicate(answer.messageId)
            SendGroupResult.UnknownKeyVersion -> GroupSendStep.UnknownKeyVersion
            SendGroupResult.Banned -> GroupSendStep.Banned
            is SendGroupResult.SlowMode -> GroupSendStep.SlowMode(answer.retryAfterSec)
            is SendGroupResult.NoConnection -> GroupSendStep.Offline(answer.link.retryDelayMs)
            is SendGroupResult.Refused -> GroupSendStep.Refused(answer.code)
        }
    }
}
