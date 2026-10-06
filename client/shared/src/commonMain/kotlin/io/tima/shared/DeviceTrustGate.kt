package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.core.network.DeviceKeyRecord
import io.tima.core.network.DeviceKeysResult
import io.tima.core.encryption.DeviceTrustCheck
import io.tima.core.encryption.RecoverySignature
import io.tima.domain.chat.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Каким устройствам собеседника можно доверять (ПЛАН-(ДУ+ИУ)-УСТРОЙСТВ-И-ИСТОРИИ ДУ3, беда «вор SIM
 * читает новые сообщения»).
 *
 * Список устройств приходит от сервера, а сервер — не якорь доверия: подложить в список
 * своё устройство мог и вор с перевыпущенной SIM, и взломанный сервер. Поэтому каждое
 * устройство проверяется здесь, своим кодом: его ключи заверены ключом подписи устройств
 * (КПУ) или ключом личности, КПУ — ключом личности, а ключ личности тот же, что мы видели у
 * этого человека в первый раз.
 *
 * **Режим задаёт сервер** (Р24, `trust_mode` в ответе): `off` — пускаем всех, как раньше;
 * `record` — пускаем всех, недоверенных пишем в журнал; `require` — недоверенным не
 * упаковывается ни ключ сообщения, ни ключ группы, и их подписи не принимаются.
 *
 * **Ключ личности запоминается при первой встрече** и лежит в настройках этого аккаунта.
 * Сервер его подменить не может: ключ личности у `user_id` не меняется никогда — смена
 * личности заводит новый `user_id` (ДУ6). Расхождение значит, что сервер лжёт, и тогда не
 * доверяем ни одному устройству этого человека.
 */
class DeviceTrustGate(private val settings: Settings) {

    private val lock = Mutex()

    /** О каком устройстве уже сказано в журнал — чтобы не писать на каждое сообщение. */
    private val said = mutableSetOf<String>()

    /**
     * Устройства из ответа, которым можно доверять при действующем режиме.
     *
     * @param userId чьи устройства.
     */
    suspend fun admit(userId: String, answer: DeviceKeysResult.Devices): List<DeviceKeyRecord> {
        val mode = answer.trustMode
        if (mode == MODE_OFF) return answer.devices
        val identity = answer.identityPub
        val remembered = known(userId)
        if (identity != null && remembered == null) remember(userId, identity)
        val changed = identity != null && remembered != null && !remembered.contentEquals(identity)
        if (changed) note(userId, "-", "ключ личности не тот, что при первой встрече — сервер подменил?", trouble = true)

        val asks = answer.signingKeys.map { DeviceTrustCheck.SigningKey(it.askId, it.askPub, it.askSig) }
        val (trusted, untrusted) = answer.devices.partition { d ->
            !changed && DeviceTrustCheck.trusted(identity, asks, d.encryptionPub, d.signingPub, d.certBy, d.certAskId, d.certSig)
        }
        for (d in untrusted) {
            val why = if (identity == null) "у аккаунта нет ключа личности" else "устройство не заверено"
            note(userId, d.deviceId, why, trouble = false, mode = mode)
        }
        return if (mode == MODE_REQUIRE) trusted else answer.devices
    }

    /**
     * Кому отдать ключи по просьбе (`recovery.msg_request`, `recovery.gk_request`) — строже
     * [admit] и **в любом режиме** (заказчик 2026-10-06): устройство заверено, либо просьба
     * подписана ключом личности из фразы над `tima.recover.v1|<[scopeId]>|<устройство>`. Не
     * всякое устройство можно заверить, и фраза остаётся его путём.
     *
     * В «записывать» [admit] пропустил бы любое — а здесь отдаётся вся переписка, и вор SIM
     * получил бы её одной просьбой. Ключ личности сверяется с запомненным, как в [admit].
     *
     * @return запись устройства из ответа либо `null` — не заверено и не подписано.
     */
    suspend fun vouched(
        userId: String,
        answer: DeviceKeysResult.Devices,
        deviceId: String,
        scopeId: String,
        signature: ByteArray?,
    ): DeviceKeyRecord? {
        val device = answer.devices.firstOrNull { it.deviceId == deviceId } ?: return null
        val identity = answer.identityPub ?: return null
        val remembered = known(userId)
        if (remembered == null) remember(userId, identity)
        if (remembered != null && !remembered.contentEquals(identity)) {
            note(userId, "-", "ключ личности не тот, что при первой встрече — сервер подменил?", trouble = true)
            return null
        }
        val asks = answer.signingKeys.map { DeviceTrustCheck.SigningKey(it.askId, it.askPub, it.askSig) }
        if (DeviceTrustCheck.trusted(identity, asks, device.encryptionPub, device.signingPub, device.certBy, device.certAskId, device.certSig)) {
            return device
        }
        if (signature != null && RecoverySignature.verify(identity, scopeId, deviceId, signature)) return device
        return null
    }

    private suspend fun known(userId: String): ByteArray? =
        runCatching { settings.all().first()[KEY_PREFIX + userId] }.getOrNull()?.let { hexToBytes(it) }

    private suspend fun remember(userId: String, identity: ByteArray) {
        runCatching { settings.put(KEY_PREFIX + userId, toHex(identity)) }
    }

    private suspend fun note(userId: String, deviceId: String, what: String, trouble: Boolean, mode: String = "") {
        val first = lock.withLock { said.add("$userId/$deviceId/$what") }
        if (!first) return
        val fields = arrayOf("кто" to userId.take(8), "устройство" to deviceId.take(8), "режим" to mode)
        if (trouble) Journal.trouble(LogCode.DEVICE_TRUST, what, *fields) else Journal.note(LogCode.DEVICE_TRUST, what, *fields)
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun hexToBytes(s: String): ByteArray? =
        if (s.length % 2 != 0) null else runCatching { ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()

    private companion object {
        const val MODE_OFF = "off"
        const val MODE_REQUIRE = "require"

        /** Имя настройки латиницей — правило про то, что приложение кладёт на диск. */
        const val KEY_PREFIX = "trust.identity."
    }
}
