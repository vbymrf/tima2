package io.tima.core.encryption

import io.tima.crypto.MessageSigner

/**
 * Подпись ключом устройства для обновления токена доступа.
 *
 * **Зачем отдельный класс, а не лямбда в сборке.** Закрытый ключ (`DeviceIdentity.key`)
 * не выходит за пределы этого модуля — так задумано и так проверяется архитектурными
 * правилами. Подписать что-либо снаружи нельзя; значит подписант живёт здесь, как и
 * [LinkSignerOverKodium] для привязки.
 *
 * Что именно подписывается — решает вызывающий: раскладку байт задаёт сеть
 * (`deviceTokenSigningBytes`), и она нормативна — зеркалит серверную байт-в-байт.
 */
class DeviceTokenSignerOverKodium(private val identity: DeviceIdentity?) {

    /** `null` — подписывать нечем: устройство не заведено. */
    fun sign(bytes: ByteArray): ByteArray? {
        val key = identity?.key ?: return null
        return MessageSigner.sign(key, bytes).getOrNull()
    }
}
