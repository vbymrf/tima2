package io.tima.app.platform

/**
 * Платформенная защита секрета устройства в покое (roadmap фазы 3:
 * Keystore / Secure Enclave). Android — AES-GCM ключом из AndroidKeyStore
 * (ключ аппаратный, из устройства не извлекается). Desktop — пока pass-through:
 * файл в профиле пользователя; DPAPI/Credential Manager — отдельная итерация.
 */
expect object SecretVault {
    fun protect(plain: ByteArray): ByteArray
    fun reveal(blob: ByteArray): ByteArray
}
