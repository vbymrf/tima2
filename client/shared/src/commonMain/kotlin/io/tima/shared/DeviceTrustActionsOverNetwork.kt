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
    /** Личности аккаунта и отмена новой (ДУ6). */
    private val users: io.tima.core.network.UsersApi? = null,
    private val userId: String,
    private val identity: DeviceIdentity,
    private val asks: AskSecrets,
    private val phone: Boolean,
    /**
     * Своё устройство заверено этим телефоном: `(deviceId, ключ шифрования)`. По нему ему
     * передаются ключи групп и история переписок (ИУ2).
     */
    private val onCertified: (String, ByteArray) -> Unit = { _, _ -> },
    /** SMS на номер аккаунта — для запрета «Начать заново» (ДУ10). */
    private val sms: io.tima.core.network.AuthApi? = null,
    /** Фраза подтверждена — копии ключей вывести свою пару (модель Matrix, М1). */
    private val onPhrase: suspend (List<String>) -> Unit = {},
    /** Копия ключей — смена пары после отключения устройства (М5). */
    private val keyCopy: KeyCopyService? = null,
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
                .also { if (it == TrustStep.Done) onPhrase(words) }
        }
        val ask = AccountSigningKey.generate()
        val askSig = IdentityTrustSigner.certifyAsk(words, ask.public) ?: return TrustStep.WrongPhrase
        val cert = ask.certify(enc, sig) ?: return TrustStep.Refused("подпись не сделалась")
        val step = keys.issueSigningKey(ask.public, askSig, cert).step()
        // Ключ сохраняется после успеха сервера: заведённый, но отвергнутый ключ заверял бы
        // устройства, которым никто не поверит.
        if (step == TrustStep.Done) {
            asks.put(ask.exportRaw())
            onPhrase(words)
        }
        return step
    }

    override suspend fun certifyCode(): String? {
        val id = selfId().ifEmpty { return null }
        return io.tima.core.network.CertifyQr.payload(id, identity.encryptionPublic, identity.signingPublic)
    }

    override suspend fun certifyByCode(code: String): TrustStep {
        val read = io.tima.core.network.CertifyQr.parse(code) ?: return TrustStep.Refused("это не код заверения")
        val raw = asks.get() ?: return TrustStep.NoKey
        val listed = when (val answer = keys.devicesOf(userId)) {
            is DeviceKeysResult.Devices -> answer.devices.firstOrNull { it.deviceId == read.deviceId }
            is DeviceKeysResult.Offline -> return TrustStep.Offline(answer.link.retryDelayMs)
            is DeviceKeysResult.Refused -> return TrustStep.Refused(answer.code)
        } ?: return TrustStep.Refused("устройство не из этого аккаунта")
        // Ключи с экрана обязаны совпасть с ключами у сервера: иначе заверили бы не то
        // устройство, что перед камерой.
        if (!listed.encryptionPub.contentEquals(read.encryptionPub) || !listed.signingPub.contentEquals(read.signingPub)) {
            return TrustStep.Refused("ключи на экране не совпадают с ключами устройства у сервера")
        }
        val cert = AccountSigningKey.fromRaw(raw).certify(read.encryptionPub, read.signingPub) ?: return TrustStep.Refused("подпись не сделалась")
        val step = keys.certifyDevice(read.deviceId, DeviceTrustCheck.BY_ASK, cert).step()
        if (step == TrustStep.Done) onCertified(read.deviceId, read.encryptionPub)
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
        val step = keys.certifyDevice(deviceId, DeviceTrustCheck.BY_ASK, cert).step()
        if (step == TrustStep.Done) onCertified(deviceId, target.wrapPub()) // ключ эпохи (ПС3)
        return step
    }

    override suspend fun replaced(): Boolean {
        val api = users ?: return false
        val me = api.identities(listOf(userId))?.get(userId) ?: return false
        // Не текущая и не отменённая — значит текущая другая, новее: начали заново.
        return !me.current && !me.cancelled
    }

    override suspend fun cancelNewIdentity(words: List<String>): TrustStep {
        val api = users ?: return TrustStep.Refused("нет сети пользователей")
        val challenge = api.identityChallenge() ?: return TrustStep.Offline(0)
        val signature = io.tima.core.encryption.IdentitySignerOverKodium.sign(words, challenge.encodeToByteArray())
            ?: return TrustStep.WrongPhrase
        return when (val answer = api.cancelNewIdentity(challenge, signature)) {
            TrustCallResult.Done -> TrustStep.Done
            is TrustCallResult.Offline -> TrustStep.Offline(answer.link.retryDelayMs)
            is TrustCallResult.Refused -> when (answer.code) {
                "bad_signature" -> TrustStep.WrongPhrase
                "device_unproven" -> TrustStep.NoKey
                "nothing_to_cancel" -> TrustStep.NothingToCancel
                else -> TrustStep.Refused(answer.code)
            }
        }
    }

    override suspend fun startAnewBanned(): Boolean? = users?.startAnewState()?.banned

    override suspend fun copyRotationDue(): Boolean = keyCopy?.rotationDue() == true

    override suspend fun copyMissing(): Boolean? = keyCopy?.missing()

    override suspend fun startCopy(words: List<String>): TrustStep = when (keyCopy?.start(words)) {
        KeyCopyService.Rotation.DONE -> TrustStep.Done
        KeyCopyService.Rotation.WRONG_PHRASE -> TrustStep.WrongPhrase
        KeyCopyService.Rotation.OFFLINE -> TrustStep.Offline(0)
        null -> TrustStep.Refused("копии ключей нет")
    }

    override suspend fun rotateCopy(words: List<String>): TrustStep = when (keyCopy?.rotate(words)) {
        KeyCopyService.Rotation.DONE -> TrustStep.Done
        KeyCopyService.Rotation.WRONG_PHRASE -> TrustStep.WrongPhrase
        KeyCopyService.Rotation.OFFLINE -> TrustStep.Offline(0)
        null -> TrustStep.Refused("копии ключей нет")
    }

    override suspend fun sendBanCode(): io.tima.domain.account.BanCode? {
        val phone = users?.startAnewState()?.phone?.takeIf { it.isNotBlank() } ?: return null
        val sent = sms?.requestSms(phone) as? io.tima.core.network.SmsRequestResult.Sent ?: return null
        return io.tima.domain.account.BanCode(sent.requestId, sent.devCode)
    }

    override suspend fun banStartAnew(words: List<String>, requestId: String, code: String): TrustStep {
        val api = users ?: return TrustStep.Refused("нет сети пользователей")
        val auth = sms ?: return TrustStep.Refused("нет SMS")
        val token = when (val v = auth.verifySms(requestId, code)) {
            is io.tima.core.network.SmsVerifyResult.Verified -> v.registrationToken
            io.tima.core.network.SmsVerifyResult.BadCode -> return TrustStep.WrongCode
            is io.tima.core.network.SmsVerifyResult.NoConnection -> return TrustStep.Offline(v.link.retryDelayMs)
            is io.tima.core.network.SmsVerifyResult.Refused -> return TrustStep.Refused(v.code)
        }
        val challenge = api.identityChallenge() ?: return TrustStep.Offline(0)
        val signature = io.tima.core.encryption.IdentitySignerOverKodium.sign(words, challenge.encodeToByteArray())
            ?: return TrustStep.WrongPhrase
        return when (val answer = api.banStartAnew(token, challenge, signature)) {
            TrustCallResult.Done -> TrustStep.Done
            is TrustCallResult.Offline -> TrustStep.Offline(answer.link.retryDelayMs)
            is TrustCallResult.Refused -> when (answer.code) {
                "bad_signature" -> TrustStep.WrongPhrase
                "bad_token" -> TrustStep.WrongCode
                else -> TrustStep.Refused(answer.code)
            }
        }
    }

    override suspend fun rereg(): io.tima.domain.account.Rereg? {
        val state = users?.reregState() ?: return null
        if (!state.active) return io.tima.domain.account.Rereg.NONE
        return io.tima.domain.account.Rereg(
            active = true, isNew = state.role == "new", windowFrom = state.windowFrom, windowTo = state.windowTo,
            disputed = state.disputed, confirmed = state.confirmed, round = state.round,
        )
    }

    override suspend fun prepareRereg(words: List<String>): io.tima.domain.account.PrepareRereg {
        fun failed(step: TrustStep) = io.tima.domain.account.PrepareRereg.Failed(step)
        val api = users ?: return failed(TrustStep.Refused("нет сети пользователей"))
        val mine = (keys.devicesOf(userId) as? DeviceKeysResult.Devices)?.identityPub ?: return failed(TrustStep.Offline(0))
        val claimed = io.tima.core.encryption.AccountIdentitiesOverKodium.fromWords(words) ?: return failed(TrustStep.WrongPhrase)
        if (!claimed.contentEquals(mine)) return failed(TrustStep.WrongPhrase)
        val phone = api.startAnewState()?.phone?.takeIf { it.isNotBlank() } ?: return failed(TrustStep.Offline(0))
        val challenge = api.identityChallenge() ?: return failed(TrustStep.Offline(0))
        val signature = io.tima.core.encryption.IdentitySignerOverKodium.sign(words, challenge.encodeToByteArray())
            ?: return failed(TrustStep.WrongPhrase)
        return io.tima.domain.account.PrepareRereg.Ready(phone, io.tima.domain.account.ReregProof(challenge, signature), words)
    }

    override suspend fun sendCode(purpose: String): io.tima.domain.account.CodeSend {
        val phone = users?.startAnewState()?.phone?.takeIf { it.isNotBlank() } ?: return io.tima.domain.account.CodeSend.Failed
        return when (val sent = sms?.requestSms(phone, purpose)) {
            is io.tima.core.network.SmsRequestResult.Sent ->
                io.tima.domain.account.CodeSend.Sent(io.tima.domain.account.BanCode(sent.requestId, sent.devCode))
            is io.tima.core.network.SmsRequestResult.Refused ->
                if (sent.code == "rate_limited") io.tima.domain.account.CodeSend.Limited else io.tima.domain.account.CodeSend.Failed
            else -> io.tima.domain.account.CodeSend.Failed
        }
    }

    override suspend fun claimRereg(words: List<String>, requestId: String, code: String): TrustStep =
        reregCall("claim", words, null, requestId, code)

    override suspend fun confirmRereg(words: List<String>, oldWords: List<String>?, requestId: String, code: String): TrustStep =
        reregCall("confirm", words, oldWords, requestId, code)

    override suspend fun phoneChange(): io.tima.domain.account.PhoneChange? {
        val state = users?.phoneChangeState() ?: return null
        if (!state.active) return io.tima.domain.account.PhoneChange.NONE
        return io.tima.domain.account.PhoneChange(
            active = true, newPhone = state.newPhone, newPhoneFull = state.newPhoneFull, mine = state.mine,
            windowFrom = state.windowFrom, windowTo = state.windowTo,
        )
    }

    override suspend fun sendPhoneChangeCode(newPhone: String?): io.tima.domain.account.CodeSend {
        val phone = newPhone ?: users?.startAnewState()?.phone?.takeIf { it.isNotBlank() }
            ?: return io.tima.domain.account.CodeSend.Failed
        val purpose = if (newPhone == null) "phone_change_start" else "phone_change_confirm"
        return when (val sent = sms?.requestSms(phone, purpose)) {
            is io.tima.core.network.SmsRequestResult.Sent ->
                io.tima.domain.account.CodeSend.Sent(io.tima.domain.account.BanCode(sent.requestId, sent.devCode))
            is io.tima.core.network.SmsRequestResult.Refused ->
                if (sent.code == "rate_limited") io.tima.domain.account.CodeSend.Limited else io.tima.domain.account.CodeSend.Failed
            else -> io.tima.domain.account.CodeSend.Failed
        }
    }

    override suspend fun startPhoneChange(newPhone: String, words: List<String>, requestId: String, code: String): TrustStep =
        phoneChangeCall(newPhone, words, requestId, code)

    override suspend fun confirmPhoneChange(words: List<String>, requestId: String, code: String): TrustStep =
        phoneChangeCall(null, words, requestId, code)

    /** Заявка ([newPhone] задан) и подтверждение смены номера (ДУ9): SMS, вызов, подпись фразой. */
    private suspend fun phoneChangeCall(newPhone: String?, words: List<String>, requestId: String, code: String): TrustStep {
        val api = users ?: return TrustStep.Refused("нет сети пользователей")
        val auth = sms ?: return TrustStep.Refused("нет SMS")
        val token = when (val v = auth.verifySms(requestId, code)) {
            is io.tima.core.network.SmsVerifyResult.Verified -> v.registrationToken
            io.tima.core.network.SmsVerifyResult.BadCode -> return TrustStep.WrongCode
            is io.tima.core.network.SmsVerifyResult.NoConnection -> return TrustStep.Offline(v.link.retryDelayMs)
            is io.tima.core.network.SmsVerifyResult.Refused -> return TrustStep.Refused(v.code)
        }
        val challenge = api.identityChallenge() ?: return TrustStep.Offline(0)
        val signature = io.tima.core.encryption.IdentitySignerOverKodium.sign(words, challenge.encodeToByteArray())
            ?: return TrustStep.WrongPhrase
        return when (val answer = api.phoneChange(newPhone, token, challenge, signature)) {
            TrustCallResult.Done -> TrustStep.Done
            is TrustCallResult.Offline -> TrustStep.Offline(answer.link.retryDelayMs)
            is TrustCallResult.Refused -> when (answer.code) {
                "bad_signature" -> TrustStep.WrongPhrase
                "phone_mismatch" -> TrustStep.WrongCode
                else -> TrustStep.Refused(answer.code)
            }
        }
    }

    /** Заявка и подтверждение (ДУ9): SMS на номер аккаунта, вызов, подписи фразами. */
    private suspend fun reregCall(action: String, words: List<String>, oldWords: List<String>?, requestId: String, code: String): TrustStep {
        val api = users ?: return TrustStep.Refused("нет сети пользователей")
        val auth = sms ?: return TrustStep.Refused("нет SMS")
        val token = when (val v = auth.verifySms(requestId, code)) {
            is io.tima.core.network.SmsVerifyResult.Verified -> v.registrationToken
            io.tima.core.network.SmsVerifyResult.BadCode -> return TrustStep.WrongCode
            is io.tima.core.network.SmsVerifyResult.NoConnection -> return TrustStep.Offline(v.link.retryDelayMs)
            is io.tima.core.network.SmsVerifyResult.Refused -> return TrustStep.Refused(v.code)
        }
        val challenge = api.identityChallenge() ?: return TrustStep.Offline(0)
        val bytes = challenge.encodeToByteArray()
        val signature = io.tima.core.encryption.IdentitySignerOverKodium.sign(words, bytes) ?: return TrustStep.WrongPhrase
        val old = oldWords?.let { io.tima.core.encryption.IdentitySignerOverKodium.sign(it, bytes) ?: return TrustStep.WrongPhrase }
        return when (val answer = api.rereg(action, token, challenge, signature, old)) {
            TrustCallResult.Done -> TrustStep.Done
            is TrustCallResult.Offline -> TrustStep.Offline(answer.link.retryDelayMs)
            is TrustCallResult.Refused -> when (answer.code) {
                "bad_signature" -> TrustStep.WrongPhrase
                "phone_mismatch" -> TrustStep.WrongCode
                else -> TrustStep.Refused(answer.code)
            }
        }
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
