package io.tima.shared

import io.tima.core.encryption.DeviceTrustCheck
import io.tima.core.network.DeviceKeysResult

/**
 * Заверено ли **это** устройство — для события «Это устройство не заверено» (решение заказчика
 * 2026-10-06: «Событие показываем как Разрешения»).
 *
 * Отчёт QMTG: телефон и ПК заказчика заведены до ДУ1 и не заверены; стенд в «требовать», и
 * собеседники молча перестали заворачивать им ключи и принимать их подписи. Человек видел
 * «сообщение недоступно» и не знал, что поправить это можно одной фразой.
 *
 * Проверка та же, что у собеседников ([DeviceTrustGate]): своё устройство заверено, только если
 * его заверение сходится с ключом личности. Верить полю «заверено» из ответа сервера здесь так же
 * незачем, как и там.
 */
object OwnDeviceTrust {

    /**
     * @return `true` — заверить нужно: сервер в «требовать», у аккаунта есть ключ личности, а это
     *   устройство не заверено; `false` — не нужно; `null` — своего устройства в ответе нет
     *   (не узнали — событие не показывается).
     */
    fun needsCertify(answer: DeviceKeysResult.Devices, signingPub: ByteArray): Boolean? {
        // В «записывать» и «выкл» незаверенное устройство работает — тревожить не о чем.
        if (answer.trustMode != MODE_REQUIRE) return false
        // Аккаунт без фразы — другая беда, и её показывает вход («нужна секретная фраза»).
        val identity = answer.identityPub ?: return false
        val own = answer.devices.firstOrNull { it.signingPub.contentEquals(signingPub) } ?: return null
        val asks = answer.signingKeys.map { DeviceTrustCheck.SigningKey(it.askId, it.askPub, it.askSig) }
        return !DeviceTrustCheck.trusted(identity, asks, own.encryptionPub, own.signingPub, own.certBy, own.certAskId, own.certSig)
    }

    private const val MODE_REQUIRE = "require"
}
