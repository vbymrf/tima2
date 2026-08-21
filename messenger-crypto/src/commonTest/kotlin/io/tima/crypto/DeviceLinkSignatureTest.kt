package io.tima.crypto

import io.kodium.Kodium
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Привязка нового устройства по QR. Раскладка байт здесь обязана совпадать с
 * server/internal/api/device_link.go (linkSigningBytes) байт-в-байт — этот тест
 * проверяет то, что доступно без запуска Go: подпись реально покрывает все поля
 * (тамперинг любого из них ловится), а не какую-то фиксированную заглушку.
 */
class DeviceLinkSignatureTest {

    private val phoneKey = Kodium.generateKeyPair()
    private val phonePub = phoneKey.getPublicKey().signingKey

    private val sessionId = "aaaaaaaa-0000-0000-0000-00000000c4a7"
    private val secret = "s3cr3t-from-qr"
    private val encPub = ByteArray(32) { it.toByte() }
    private val signPub = ByteArray(32) { (it * 3).toByte() }

    private fun sign(sid: String = sessionId, sec: String = secret, enc: ByteArray = encPub, sig: ByteArray = signPub) =
        MessageSigner.sign(phoneKey, DeviceLinkSignature.signingBytes(sid, sec, enc, sig)).getOrThrow()

    @Test
    fun `подпись проходит проверку по ключу подтверждающего устройства`() {
        val signature = sign()
        assertTrue(MessageSigner.verify(phonePub, DeviceLinkSignature.signingBytes(sessionId, secret, encPub, signPub), signature))
    }

    @Test
    fun `подмена session_id ломает проверку`() {
        val signature = sign()
        assertFalse(
            MessageSigner.verify(phonePub, DeviceLinkSignature.signingBytes("other-session", secret, encPub, signPub), signature),
        )
    }

    @Test
    fun `подмена secret ломает проверку`() {
        val signature = sign()
        assertFalse(
            MessageSigner.verify(phonePub, DeviceLinkSignature.signingBytes(sessionId, "wrong-secret", encPub, signPub), signature),
        )
    }

    @Test
    fun `подмена ключей нового устройства ломает проверку`() {
        val signature = sign()
        val swapped = encPub.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(
            MessageSigner.verify(phonePub, DeviceLinkSignature.signingBytes(sessionId, secret, swapped, signPub), signature),
        )
    }

    @Test
    fun `чужой ключ подтверждающего устройства подпись не проходит`() {
        val impostor = Kodium.generateKeyPair()
        val signature = sign()
        assertFalse(
            MessageSigner.verify(
                impostor.getPublicKey().signingKey,
                DeviceLinkSignature.signingBytes(sessionId, secret, encPub, signPub),
                signature,
            ),
        )
    }

    @Test
    fun `разные ключи и сессии дают разные подписываемые байты`() {
        val a = DeviceLinkSignature.signingBytes(sessionId, secret, encPub, signPub)
        val b = DeviceLinkSignature.signingBytes(sessionId, secret, encPub, ByteArray(32))
        assertNotEquals(a.toList(), b.toList())
    }
}
