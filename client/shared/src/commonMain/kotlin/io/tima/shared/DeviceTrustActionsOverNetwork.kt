package io.tima.shared

import io.tima.core.encryption.AccountIdentitiesOverKodium
import io.tima.core.encryption.AccountSigningKey
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.encryption.DeviceTrustCheck
import io.tima.core.encryption.IdentityTrustSigner
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.KeysApi
import io.tima.core.network.TrustCallResult
import io.tima.domain.account.DeviceTrustActions
import io.tima.domain.account.TrustStep

/**
 * Доверие к своим устройствам — сеть, ключи и хранилище вместе (ДУ5).
 *
 * @param phone держит ли это устройство ключ подписи устройств (Р12: только телефон).
 */
class DeviceTrustActionsOverNetwork(
    private val keys: KeysApi,
    private val userId: String,
    private val identity: DeviceIdentity,
    private val asks: AskSecrets,
    private val phone: Boolean,
) : DeviceTrustActions {

    override fun holdsKey(): Boolean = phone && asks.get() != null

    override suspend fun confirmWithPhrase(words: List<String>): TrustStep {
        // Фраза должна быть этого аккаунта: заверить устройство чужой личностью можно, но
        // сервер такое свидетельство не примет, а человеку надо сказать прямо — фраза не та.
        val pub = AccountIdentitiesOverKodium.fromWords(words) ?: return TrustStep.WrongPhrase
        val mine = when (val answer = keys.devicesOf(userId)) {
            is DeviceKeysResult.Devices -> answer.identityPub
            is DeviceKeysResult.Offline -> return TrustStep.Offline(answer.link.retryDelayMs)
            is DeviceKeysResult.Refused -> return TrustStep.Refused(answer.code)
        }
        if (mine != null && !mine.contentEquals(pub)) return TrustStep.WrongPhrase

        val enc = identity.encryptionPublic
        val sig = identity.signingPublic
        if (!phone) {
            val cert = IdentityTrustSigner.certifyDevice(words, enc, sig) ?: return TrustStep.WrongPhrase
            return keys.certifyDevice(selfId(), DeviceTrustCheck.BY_IDENTITY, cert).step()
        }
        val ask = AccountSigningKey.generate()
        val askSig = IdentityTrustSigner.certifyAsk(words, ask.public) ?: return TrustStep.WrongPhrase
        val cert = ask.certify(enc, sig) ?: return TrustStep.Refused("подпись не сделалась")
        val step = keys.issueSigningKey(ask.public, askSig, cert).step()
        // Ключ сохраняется после успеха сервера: заведённый, но отвергнутый ключ заверял бы
        // устройства, которым никто не поверит.
        if (step == TrustStep.Done) asks.put(ask.exportRaw())
        return step
    }

    override suspend fun certify(deviceId: String): TrustStep {
        val raw = asks.get() ?: return TrustStep.NoKey
        val ask = AccountSigningKey.fromRaw(raw)
        val target = when (val answer = keys.devicesOf(userId)) {
            is DeviceKeysResult.Devices -> answer.devices.firstOrNull { it.deviceId == deviceId }
                ?: return TrustStep.Refused("устройство не найдено")
            is DeviceKeysResult.Offline -> return TrustStep.Offline(answer.link.retryDelayMs)
            is DeviceKeysResult.Refused -> return TrustStep.Refused(answer.code)
        }
        val cert = ask.certify(target.encryptionPub, target.signingPub) ?: return TrustStep.Refused("подпись не сделалась")
        return keys.certifyDevice(deviceId, DeviceTrustCheck.BY_ASK, cert).step()
    }

    /**
     * Своё `device_id` — из ответа сервера по своим ключам: сессию сюда не тащим ради одного
     * значения, а ключи и так у нас.
     */
    private suspend fun selfId(): String {
        val answer = keys.devicesOf(userId) as? DeviceKeysResult.Devices ?: return ""
        return answer.devices.firstOrNull { it.signingPub.contentEquals(identity.signingPublic) }?.deviceId.orEmpty()
    }

    private fun TrustCallResult.step(): TrustStep = when (this) {
        TrustCallResult.Done -> TrustStep.Done
        is TrustCallResult.Offline -> TrustStep.Offline(link.retryDelayMs)
        is TrustCallResult.Refused -> if (code == "bad_signature") TrustStep.WrongPhrase else TrustStep.Refused(code)
    }
}
