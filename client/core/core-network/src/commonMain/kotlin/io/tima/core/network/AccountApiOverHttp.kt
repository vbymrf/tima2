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
        when (val исход = auth.requestSms(phone)) {
            is SmsRequestResult.Sent -> CodeRequestStep.CodeRequested(исход.requestId, исход.devCode)
            is SmsRequestResult.BadPhone -> CodeRequestStep.BadPhone(исход.reason)
            is SmsRequestResult.NoConnection -> CodeRequestStep.Offline(исход.link.retryDelayMs)
            is SmsRequestResult.Refused -> CodeRequestStep.Refused(исход.code)
        }

    override suspend fun submitCode(requestId: String, code: String): CodeSubmitStep =
        when (val исход = auth.verifySms(requestId, code)) {
            is SmsVerifyResult.Verified -> CodeSubmitStep.Accepted(исход.registrationToken)
            SmsVerifyResult.BadCode -> CodeSubmitStep.WrongCode
            is SmsVerifyResult.NoConnection -> CodeSubmitStep.Offline(исход.link.retryDelayMs)
            is SmsVerifyResult.Refused -> CodeSubmitStep.Refused(исход.code)
        }

    override suspend fun createDevice(
        registrationToken: String,
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        identityPub: ByteArray?,
        platform: String,
    ): DeviceCreateStep = when (
        val исход = auth.register(registrationToken, encryptionPub, signingPub, identityPub, platform)
    ) {
        is RegisterResult.Registered ->
            DeviceCreateStep.Created(исход.userId, исход.deviceId, исход.accessToken)
        RegisterResult.IdentityMismatch -> DeviceCreateStep.IdentityMismatch
        RegisterResult.TokenExpired -> DeviceCreateStep.TokenExpired
        is RegisterResult.NoConnection -> DeviceCreateStep.Offline(исход.link.retryDelayMs)
        is RegisterResult.Refused -> DeviceCreateStep.Refused(исход.code)
    }
}
