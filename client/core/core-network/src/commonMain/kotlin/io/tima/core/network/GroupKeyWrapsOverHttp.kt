package io.tima.core.network

import io.tima.domain.chat.GroupKeyWraps
import io.tima.domain.chat.GroupKeyWrapsStep
import io.tima.domain.chat.WrappedGroupKeyInfo

/**
 * Обёртки группового ключа по HTTP — переходник к порту домена.
 *
 * Тонкий намеренно: вся выборка «что адресовано этому устройству» происходит на сервере,
 * и разворачивать обёртку здесь нельзя — в `core-network` нет и не должно быть ключа
 * устройства. Разворачивает `core-encryption`, за отдельным портом.
 */
class GroupKeyWrapsOverHttp(private val api: GroupKeysApi) : GroupKeyWraps {

    override suspend fun mine(groupId: String, sinceVersion: Int): GroupKeyWrapsStep =
        when (val ответ = api.mine(groupId, sinceVersion)) {
            is GroupKeysResult.Keys -> GroupKeyWrapsStep.Wraps(
                wraps = ответ.keys.map {
                    WrappedGroupKeyInfo(it.gkVersion, it.senderEphemeralPub, it.wrapped)
                },
                currentVersion = ответ.currentVersion,
            )
            is GroupKeysResult.NoConnection -> GroupKeyWrapsStep.Offline(ответ.link.retryDelayMs)
            is GroupKeysResult.Refused -> GroupKeyWrapsStep.Refused(ответ.code)
        }
}
