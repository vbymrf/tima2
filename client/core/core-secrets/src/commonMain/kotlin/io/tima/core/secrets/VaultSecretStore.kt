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

    override fun saveSession(session: Session) {
        val части = listOf(session.userId, session.deviceId, session.accessToken)
        require(части.none { it.isEmpty() }) { "пустое поле сессии: $части" }
        require(части.none { it.contains(SEPARATOR) }) {
            "перевод строки внутри значения сессии — такого не бывает у UUID и base64url"
        }
        vault.put(SESSION, части.joinToString(SEPARATOR).encodeToByteArray())
    }

    override fun session(): Session? {
        val части = vault.get(SESSION)?.decodeToString()?.split(SEPARATOR) ?: return null
        // Не «пустая сессия»: испорченная запись означает, что хранилище отдало не то, и
        // молча начать с чистого листа — значит завести второе устройство при живом
        // первом.
        if (части.size != 3 || части.any { it.isEmpty() }) {
            throw SecretVaultFailure("запись сессии испорчена: ${части.size} частей")
        }
        return Session(userId = части[0], deviceId = части[1], accessToken = части[2])
    }

    /** Выход из аккаунта: и сессия, и секрет устройства. */
    fun clear() {
        vault.remove(SESSION)
        vault.remove(Secrets.DEVICE_SECRET)
    }

    private companion object {
        val SESSION = SecretAlias("session.v1")
        const val SEPARATOR = "\n"

        /** Тот же размер, что у `core-encryption`; проверяется на входе. */
        const val DEVICE_SECRET_BYTES = 32
    }
}
