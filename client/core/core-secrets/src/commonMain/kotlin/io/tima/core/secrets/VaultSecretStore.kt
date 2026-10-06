package io.tima.core.secrets

import io.tima.domain.account.DeviceSecretStore
import io.tima.domain.account.Session

/**
 * Переходник: порт `domain-account` над хранилищем платформы.
 *
 * **Токен сессии лежит здесь, а не в настройках.** Это JWT устройства: с ним можно
 * отправлять от имени человека, пока он не истёк. Файл настроек читается любым
 * процессом того же пользователя, хранилище платформы — нет.
 *
 * Разбор нарочно самый простой, какой возможен: три значения через перевод строки.
 * Ни одно из них перевода строки содержать не может — идентификаторы это UUID, токен
 * это base64url, — и проверка на чтении это подтверждает. JSON тут дал бы зависимость
 * ради трёх строк.
 */
class VaultSecretStore(private val vault: SecretVault) : DeviceSecretStore {

    override fun hasDevice(): Boolean = session() != null

    override fun saveDeviceSecret(secret: ByteArray) {
        require(secret.size == DEVICE_SECRET_BYTES) {
            "секрет устройства обязан быть $DEVICE_SECRET_BYTES байт, а не ${secret.size}"
        }
        vault.put(Secrets.DEVICE_SECRET, secret)
    }

    /** Секрет устройства; `null` на первом запуске. */
    fun deviceSecret(): ByteArray? = vault.get(Secrets.DEVICE_SECRET)

    override fun saveAskSecret(secret: ByteArray) {
        require(secret.size == DEVICE_SECRET_BYTES) { "ключ подписи устройств обязан быть $DEVICE_SECRET_BYTES байт" }
        vault.put(Secrets.ASK_SECRET, secret)
    }

    override fun askSecret(): ByteArray? = vault.get(Secrets.ASK_SECRET)

    /** Секрет копии ключей с эпохой (Р46) — только на телефонах. */
    fun saveKeyCopySecret(epoch: Int, secret: ByteArray) {
        require(epoch >= 1 && secret.isNotEmpty()) { "секрет копии: эпоха с единицы и непустой ключ" }
        val head = byteArrayOf((epoch ushr 24).toByte(), (epoch ushr 16).toByte(), (epoch ushr 8).toByte(), epoch.toByte())
        vault.put(Secrets.KEY_COPY_SECRET, head + secret)
    }

    /** `эпоха → закрытый ключ копии`; `null` — не сохранён. */
    fun keyCopySecret(): Pair<Int, ByteArray>? {
        val raw = vault.get(Secrets.KEY_COPY_SECRET) ?: return null
        if (raw.size <= 4) return null
        val epoch = ((raw[0].toInt() and 0xff) shl 24) or ((raw[1].toInt() and 0xff) shl 16) or
            ((raw[2].toInt() and 0xff) shl 8) or (raw[3].toInt() and 0xff)
        return epoch to raw.copyOfRange(4, raw.size)
    }

    /** Эпохи, чьи ключи шифрования лежат здесь (ПЛАН-(ПС) ПС3). */
    fun epochKeyEpochs(): List<String> =
        vault.get(Secrets.EPOCH_KEYS)?.decodeToString()?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    fun epochKeySecret(epoch: String): ByteArray? = vault.get(Secrets.epochKey(epoch))

    /** Секрет пишется ДО перечня: перечень без секрета хуже, чем секрет без перечня. */
    fun saveEpochKeySecret(epoch: String, secret: ByteArray) {
        require(secret.size == DEVICE_SECRET_BYTES) { "ключ эпохи обязан быть $DEVICE_SECRET_BYTES байт" }
        vault.put(Secrets.epochKey(epoch), secret)
        val all = (epochKeyEpochs() + epoch).distinct()
        vault.put(Secrets.EPOCH_KEYS, all.joinToString(",").encodeToByteArray())
    }

    /** Уничтожить ключ эпохи: дальше обёртки под него не открыть никому, и это цель (ПС3). */
    fun removeEpochKeySecret(epoch: String) {
        vault.remove(Secrets.epochKey(epoch))
        vault.put(Secrets.EPOCH_KEYS, (epochKeyEpochs() - epoch).joinToString(",").encodeToByteArray())
    }

    override fun saveSession(session: Session) {
        val parts = listOf(session.userId, session.deviceId, session.accessToken)
        require(parts.none { it.isEmpty() }) { "пустое поле сессии: $parts" }
        require(parts.none { it.contains(SEPARATOR) }) {
            "перевод строки внутри значения сессии — такого не бывает у UUID и base64url"
        }
        vault.put(SESSION, parts.joinToString(SEPARATOR).encodeToByteArray())
    }

    override fun session(): Session? {
        val parts = vault.get(SESSION)?.decodeToString()?.split(SEPARATOR) ?: return null
        // Не «пустая сессия»: испорченная запись означает, что хранилище отдало не то, и
        // молча начать с чистого листа — значит завести второе устройство при живом
        // первом.
        if (parts.size != 3 || parts.any { it.isEmpty() }) {
            throw SecretVaultFailure("запись сессии испорчена: ${parts.size} частей")
        }
        return Session(userId = parts[0], deviceId = parts[1], accessToken = parts[2])
    }

    /** Выход из аккаунта: и сессия, и секрет устройства. */
    fun clear() {
        vault.remove(SESSION)
        vault.remove(Secrets.DEVICE_SECRET)
        vault.remove(Secrets.ASK_SECRET)
        vault.remove(Secrets.KEY_COPY_SECRET)
        epochKeyEpochs().forEach { vault.remove(Secrets.epochKey(it)) }
        vault.remove(Secrets.EPOCH_KEYS)
    }

    private companion object {
        val SESSION = SecretAlias("session.v1")
        const val SEPARATOR = "\n"

        /** Тот же размер, что у `core-encryption`; проверяется на входе. */
        const val DEVICE_SECRET_BYTES = 32
    }
}
