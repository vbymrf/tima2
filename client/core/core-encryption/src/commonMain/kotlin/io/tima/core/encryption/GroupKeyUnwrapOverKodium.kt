package io.tima.core.encryption

import io.tima.crypto.GroupKeyManager
import io.tima.domain.chat.GroupKeyUnwrap

/**
 * Разворачивание обёртки группового ключа — переходник к порту `domain-chat`.
 *
 * Живёт здесь, а не в `core-network`, потому что для разворачивания нужен закрытый ключ
 * устройства, а он не выходит за пределы этого модуля. Сеть обёртку только приносит.
 *
 * **Провал возвращается как `null`, а не исключением.** Обёртка, которая не развернулась, —
 * обычное дело: она могла быть испорчена, могла быть адресована прежнему ключу устройства.
 * Отличать эти случаи домену незачем — делать он будет одно и то же, — а исключение
 * прервало бы разбор остальных обёрток той же выдачи.
 */
class GroupKeyUnwrapOverKodium(private val me: DeviceIdentity) : GroupKeyUnwrap {

    override fun unwrap(senderEphemeralPub: ByteArray, wrapped: ByteArray): ByteArray? =
        GroupKeyManager.unwrapGroupKey(me.key, senderEphemeralPub, wrapped).getOrNull()
}
