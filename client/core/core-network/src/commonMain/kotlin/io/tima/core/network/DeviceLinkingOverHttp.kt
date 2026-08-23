package io.tima.core.network

import io.tima.domain.account.DeviceLinkConfirm
import io.tima.domain.account.DeviceLinkStart
import io.tima.domain.account.LinkClaimStep
import io.tima.domain.account.LinkCode
import io.tima.domain.account.LinkConfirmStep
import io.tima.domain.account.LinkStartStep

/**
 * Привязка устройства по HTTP — переходники к портам домена.
 *
 * Отдельный слой не для красоты: типы домена не знают ни про статусы, ни про коды ошибок,
 * и это то самое место, где «403 с кодом not_a_phone» превращается в «подтвердить может
 * только телефон». Смешай их — и правка адреса ручки станет правкой правил продукта.
 */
class DeviceLinkStartOverHttp(private val api: LinkStartApi) : DeviceLinkStart {

    override suspend fun start(
        encryptionPub: ByteArray,
        signingPub: ByteArray,
        deviceName: String,
    ): LinkStartStep = when (val ответ = api.start(encryptionPub, signingPub, deviceName)) {
        is LinkStartResult.Started -> LinkStartStep.Started(ответ.sessionId, ответ.qrPayload, ответ.claimToken)
        is LinkStartResult.NoConnection -> LinkStartStep.Offline(ответ.link.retryDelayMs)
        is LinkStartResult.Refused -> LinkStartStep.Refused(ответ.code)
    }

    override suspend fun claim(sessionId: String, claimToken: String): LinkClaimStep =
        when (val ответ = api.claim(sessionId, claimToken)) {
            is LinkClaimResult.Claimed -> LinkClaimStep.Claimed(ответ.userId, ответ.deviceId, ответ.accessToken)
            LinkClaimResult.NotReady -> LinkClaimStep.NotReady
            is LinkClaimResult.NoConnection -> LinkClaimStep.Offline(ответ.link.retryDelayMs)
            is LinkClaimResult.Refused -> LinkClaimStep.Refused(ответ.code)
        }
}

class DeviceLinkConfirmOverHttp(private val api: LinkConfirmApi) : DeviceLinkConfirm {

    /** Формат кода сетевой, поэтому разбор живёт здесь, а не в домене. */
    override fun parse(code: String): LinkCode? = LinkQr.parse(code)?.let {
        LinkCode(it.sessionId, it.secret, it.encryptionPub, it.signingPub, it.deviceName)
    }

    override suspend fun confirm(sessionId: String, secret: String, signature: ByteArray): LinkConfirmStep =
        when (val ответ = api.confirm(sessionId, secret, signature)) {
            is LinkConfirmResult.Confirmed -> LinkConfirmStep.Confirmed(ответ.deviceId)
            LinkConfirmResult.NotAPhone -> LinkConfirmStep.NotAPhone
            LinkConfirmResult.SessionGone -> LinkConfirmStep.SessionGone
            LinkConfirmResult.BadSignature -> LinkConfirmStep.BadSignature
            is LinkConfirmResult.NoConnection -> LinkConfirmStep.Offline(ответ.link.retryDelayMs)
            is LinkConfirmResult.Refused -> LinkConfirmStep.Refused(ответ.code)
        }
}
