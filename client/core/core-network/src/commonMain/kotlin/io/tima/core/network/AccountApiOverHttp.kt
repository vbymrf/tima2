package io.tima.core.network

import io.tima.domain.account.AccountApi
import io.tima.domain.account.CodeRequestStep
import io.tima.domain.account.CodeSubmitStep
import io.tima.domain.account.DeviceCreateStep

/**
 * Переходник: порт `domain-account` над [AuthApi].
 *
 * **Зачем он вообще, если исходы уже разобраны в [AuthApi].** Затем, что там они
 * названы словами HTTP (`status`, `code`), а здесь — словами продукта. Без перевода
 * `domain` пришлось бы знать про коды ответов, то есть перестал бы быть слоем: правка
 * адреса ручки становилась бы правкой правил регистрации.
 *
 * Направление зависимости — то же, что у `core-database`, реализующего `OutboxStore`:
 * слой данных реализует объявленное выше.
 */
class AccountApiOverHttp(private val auth: AuthApi) : AccountApi {

    override suspend fun requestCode(phone: String): CodeRequestStep =
        when (val outcome = auth.requestSms(phone)) {
            is SmsRequestResult.Sent -> CodeRequestStep.CodeRequested(outcome.requestId, outcome.devCode)
            is SmsRequestResult.BadPhone -> CodeRequestStep.BadPhone(outcome.reason)
            is SmsRequestResult.NoConnection -> CodeRequestStep.Offline(outcome.link.retryDelayMs)
            is SmsRequestResult.Refused -> CodeRequestStep.Refused(outcome.code)
        }

    override suspend fun submitCode(requestId: String, code: String): CodeSubmitStep =
        when (val outcome = auth.verifySms(requestId, code)) {
            is SmsVerifyResult.Verified -> CodeSubmitStep.Accepted(outcome.registrationToken)
            SmsVerifyResult.BadCode -> CodeSubmitStep.WrongCode
            is SmsVerifyResult.NoConnection -> CodeSubmitStep.Offline(outcome.link.retryDelayMs)
            is SmsVerifyResult.Refused -> CodeSubmitStep.Refused(outcome.code)
        }

    override suspend fun createDevice(
        registrationToken: String,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        identityPub: ByteArray?,
        platform: String,
        forceNewIdentity: Boolean,
    ): DeviceCreateStep = when (
        val outcome = auth.register(
            registrationToken = registrationToken,
            encryptionPub = encryptionPub,
            signingPub = signingPub,
            identityPub = identityPub,
            platform = platform,
            forceNewIdentity = forceNewIdentity,
        )
    ) {
        is RegisterResult.Registered ->
            DeviceCreateStep.Created(outcome.userId, outcome.deviceId, outcome.accessToken)
        RegisterResult.IdentityMismatch -> DeviceCreateStep.IdentityMismatch
        RegisterResult.TokenExpired -> DeviceCreateStep.TokenExpired
        is RegisterResult.NoConnection -> DeviceCreateStep.Offline(outcome.link.retryDelayMs)
        is RegisterResult.Refused -> DeviceCreateStep.Refused(outcome.code)
    }
}
