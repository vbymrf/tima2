package io.tima.core.network

import io.tima.domain.chat.GroupKeyRecovery
import io.tima.domain.chat.RecoveryStep

/**
 * Запрос недостающих версий GK по HTTP — переходник к порту домена.
 *
 * Подпись ключом личности здесь не собирается: ключ выводится из двенадцати слов и на
 * устройстве не лежит. Отказ `bad_identity_sig` переводится в отдельный исход, чтобы
 * экран мог попросить фразу, а не показать «ошибку».
 */
class GroupKeyRecoveryOverHttp(private val api: GroupKeyRecoveryApi) : GroupKeyRecovery {

    override suspend fun request(groupId: String): RecoveryStep =
        when (val ответ = api.request(groupId)) {
            is RecoverResult.Requested -> RecoveryStep.Requested(ответ.versions, ответ.helpers)
            RecoverResult.NeedsSecretPhrase -> RecoveryStep.NeedsSecretPhrase
            RecoverResult.NotMember -> RecoveryStep.NotMember
            is RecoverResult.NoConnection -> RecoveryStep.Offline(ответ.link.retryDelayMs)
            is RecoverResult.Refused -> RecoveryStep.Refused(ответ.code)
        }
}
