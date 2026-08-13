package io.tima.crypto

import java.security.MessageDigest

/**
 * Привязка нового устройства по QR (key-lifecycle.md §2; ПЛАН-РЕФАКТОРИНГА.md).
 *
 * Новое устройство (аккаунта ещё нет) показывает QR; уже авторизованное
 * доверенное устройство сканирует его и одним запросом (`/api/v1/link/confirm`)
 * добавляет владельца QR как своё новое устройство. Подтверждающее устройство
 * подписывает данные ИЗ QR своим уже зарегистрированным Ed25519-ключом — сервер
 * проверяет подпись по этому же ключу, поэтому расхождение (в том числе
 * случайное — разбор QR разошёлся с тем, что реально хранится в сессии на
 * сервере) само проявляется как отказ подписи, а не тихо теряется.
 *
 * Раскладка байт зеркалит `server/internal/api/device_link.go` (`linkSigningBytes`)
 * байт-в-байт — расхождение означает, что подпись, сделанная клиентом, не
 * пройдёт проверку на сервере.
 */
object DeviceLinkSignature {

    /** Подписываемые байты confirm — домен-разделитель + session_id + secret + ключи нового устройства. */
    fun signingBytes(sessionId: String, secret: String, encryptionPub: ByteArray, signingPub: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("TIMA-DEVICE-LINK-v1".encodeToByteArray())
        digest.update(byteArrayOf(0))
        digest.update(sessionId.encodeToByteArray())
        digest.update(byteArrayOf(0))
        digest.update(secret.encodeToByteArray())
        digest.update(byteArrayOf(0))
        digest.update(encryptionPub)
        digest.update(signingPub)
        return digest.digest()
    }
}
