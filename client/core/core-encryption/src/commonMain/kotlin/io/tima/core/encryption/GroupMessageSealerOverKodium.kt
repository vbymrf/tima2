package io.tima.core.encryption

import io.tima.crypto.GroupMessageMeta
import io.tima.crypto.MessageContent
import io.tima.domain.chat.GroupMessageSealer
import io.tima.domain.chat.SealedGroupBytes

/**
 * Сборка сообщения группы — переходник к порту `domain-chat`.
 *
 * Держит идентификаторы отправителя, потому что они входят в подпись: подменить их
 * серверу нельзя, но и собрать сообщение без них невозможно.
 */
class GroupMessageSealerOverKodium(
    private val senderId: String,
    private val senderDeviceId: String,
    private val identity: DeviceIdentity,
) : GroupMessageSealer {

    override fun seal(
        groupId: String,
        gkVersion: Int,
        key: ByteArray,
        text: String,
        createdAtUnixMs: Long,
    ): SealedGroupBytes? {
        val meta = GroupMessageMeta(
            groupId = groupId,
            senderId = senderId,
            senderDevice = senderDeviceId,
            kind = KIND_TEXT,
            createdAtUnixMs = createdAtUnixMs,
            gkVersion = gkVersion,
        )
        // Нулевая версия ключа значит «открытое сообщение» (ADR-0019): шифра нет,
        // подпись есть. Это не обход шифрования, а его отсутствие по назначению —
        // такое сообщение читает тот, кому ключа не дадут.
        val assembled = if (gkVersion == 0) {
            GroupMessages.sealPlain(MessageContent.text(text), meta, identity).getOrNull()
        } else {
            GroupMessages.seal(MessageContent.text(text), meta, identity, key).getOrNull()
        } ?: return null
        return SealedGroupBytes(payload = assembled.payload, signature = assembled.signature)
    }

    private companion object {
        /** `CK_TEXT` из `envelope.proto`. */
        const val KIND_TEXT = 1
    }
}
