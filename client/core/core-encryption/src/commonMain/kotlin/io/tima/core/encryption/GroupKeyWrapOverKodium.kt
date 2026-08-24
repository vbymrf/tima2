package io.tima.core.encryption

import io.tima.domain.chat.GroupKeyWrapForDevice
import io.tima.domain.chat.SharedKeyBytes

/**
 * Обёртывание версии GK под чужое устройство — переходник к порту `domain-chat`.
 *
 * Живёт в `core-encryption`, потому что порождает эфемерную пару: криптография не должна
 * появляться ни в сети, ни в домене. Провал возвращается `null` — испорченный открытый
 * ключ устройства не повод ронять раздачу остальных версий.
 */
object GroupKeyWrapOverKodium : GroupKeyWrapForDevice {

    override fun wrap(recipientEncryptionPub: ByteArray, key: ByteArray): SharedKeyBytes? =
        GroupKeyShares.wrap(recipientEncryptionPub, key).getOrNull()
            ?.let { SharedKeyBytes(it.senderEphemeralPub, it.wrapped) }
}
