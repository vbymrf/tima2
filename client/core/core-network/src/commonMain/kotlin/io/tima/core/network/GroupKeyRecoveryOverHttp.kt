package io.tima.core.network

import io.tima.domain.chat.GroupKeyRecovery
import io.tima.domain.chat.GroupKeyShareUpload
import io.tima.domain.chat.RecoveryStep
import io.tima.domain.chat.ShareStep
import io.tima.domain.chat.SharedVersion

/**
 * Запрос недостающих версий GK по HTTP — переходник к порту домена.
 *
 * Подпись ключом личности здесь не собирается: ключ выводится из двенадцати слов и на
 * устройстве не лежит. Отказ `bad_identity_sig` переводится в отдельный исход, чтобы
 * экран мог попросить фразу, а не показать «ошибку».
 */
class GroupKeyRecoveryOverHttp(
    private val api: GroupKeyRecoveryApi,
    /**
     * Подпись запроса словами аккаунта. `null` — подписывать нечем, и запрос уйдёт без
     * подписи: у аккаунта без фразы это законный путь.
     *
     * Отдельной ручкой, а не внутри: вывод ключа из слов живёт в `core-encryption`, и
     * тянуть криптографию в сеть значило бы ломать границу слоя.
     */
    private val подпись: (String, List<String>) -> String? = { _, _ -> null },
) :
    GroupKeyRecovery, GroupKeyShareUpload {

    override suspend fun provide(
        groupId: String,
        requesterDevice: String,
        keys: List<SharedVersion>,
    ): ShareStep {
        val ответ = api.provide(
            groupId = groupId,
            requesterDevice = requesterDevice,
            keys = keys.map { ProvidedKey(it.gkVersion, it.senderEphemeralPub, it.wrapped) },
        )
        return when (ответ) {
            is ProvideResult.Provided -> ShareStep.Shared(ответ.versions)
            is ProvideResult.NoConnection -> ShareStep.Offline(ответ.link.retryDelayMs)
            is ProvideResult.Refused -> ShareStep.Refused(ответ.code)
        }
    }


    override suspend fun request(groupId: String, фраза: List<String>?): RecoveryStep =
        when (val ответ = api.request(groupId, фраза?.let { подпись(groupId, it) })) {
            is RecoverResult.Requested -> RecoveryStep.Requested(ответ.versions, ответ.helpers)
            RecoverResult.NeedsSecretPhrase -> RecoveryStep.NeedsSecretPhrase
            RecoverResult.NotMember -> RecoveryStep.NotMember
            is RecoverResult.NoConnection -> RecoveryStep.Offline(ответ.link.retryDelayMs)
            is RecoverResult.Refused -> RecoveryStep.Refused(ответ.code)
        }
}
