package io.tima.domain.account

/**
 * Свои устройства: посмотреть и отключить.
 *
 * **Появилось вместе с привязкой, и это не совпадение.** Подключить устройство стало делом
 * одного скана — значит отключать человек должен уметь сам, не через поддержку и не через
 * базу. Иначе список устройств растёт, а убрать из него нечем.
 */
class MyDevices(private val book: DeviceBook) {

    suspend fun list(): DevicesStep = book.mine()

    /**
     * Отключить устройство.
     *
     * Последнее отключить нельзя — аккаунт остался бы без единой точки входа, и вернуться
     * в него можно было бы только по секретной фразе. Запрещает это сервер, а отдельный
     * исход нужен, чтобы сказать человеку именно это.
     */
    suspend fun revoke(deviceId: String): RevokeStep = book.revoke(deviceId)
}

/** Порт к серверу. Реализует `core-network`. */
interface DeviceBook {
    suspend fun mine(): DevicesStep
    suspend fun revoke(deviceId: String): RevokeStep
}

/** Устройство аккаунта. */
class AccountDevice(
    val deviceId: String,
    val name: String,
    /** Когда заведено, как это сказал сервер. Разбирать дату здесь нечем и незачем. */
    val createdAt: String?,
    /** Это устройство: отключить его — значит выйти из аккаунта здесь. */
    val current: Boolean,
)

/** Чем закончился запрос списка. */
sealed interface DevicesStep {
    data class Devices(val devices: List<AccountDevice>) : DevicesStep
    data class Offline(val retryAfterMs: Long) : DevicesStep
    data class Refused(val reason: String) : DevicesStep
}

/** Чем закончилось отключение. */
sealed interface RevokeStep {
    data object Revoked : RevokeStep

    /** Последнее устройство аккаунта. */
    data object LastDevice : RevokeStep

    /** Уже отключено — список просто устарел. */
    data object Gone : RevokeStep
    data class Offline(val retryAfterMs: Long) : RevokeStep
    data class Refused(val reason: String) : RevokeStep
}
