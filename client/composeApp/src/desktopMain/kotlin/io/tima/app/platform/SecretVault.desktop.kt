package io.tima.app.platform

// Windows: защищённого хранилища у JVM из коробки нет — секрет остаётся в файле
// профиля пользователя (~/.tima). DPAPI / Credential Manager — отдельная итерация.
actual object SecretVault {
    actual fun protect(plain: ByteArray): ByteArray = plain
    actual fun reveal(blob: ByteArray): ByteArray = blob
}
