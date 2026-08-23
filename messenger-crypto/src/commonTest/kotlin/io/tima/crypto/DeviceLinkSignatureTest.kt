package io.tima.crypto

import io.kodium.Kodium
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Привязка нового устройства по QR. Раскладка байт здесь обязана совпадать с
 * server/internal/api/device_link.go (linkSigningBytes) байт-в-байт.
 *
 * Проверяется двумя способами, и они дополняют друг друга:
 *
 * 1. **Вектор** — закреплённый sha256 подписываемых байт. Он посчитан не нашим кодом, а
 *    сторонним sha256 по раскладке, выписанной из исходника Go. Без него все остальные
 *    проверки согласованы сами с собой: подпись сойдётся с проверкой, обе будут считать
 *    одни и те же неверные байты, и узнается это отказом сервера `bad_signature` — без
 *    единого указания на причину. Правило `crypto-invariants.mdc` требует ровно этого:
 *    «change one side, change the other in the same commit, and prove it with a vector».
 * 2. **Тамперинг** — подпись реально покрывает все поля, а не какую-то их часть.
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

    /**
     * Вектор: sha256 подписываемых байт для закреплённого входа.
     *
     * Значение получено сторонним sha256 по раскладке из `linkSigningBytes`:
     * `"TIMA-DEVICE-LINK-v1" || 0 || session_id || 0 || secret || 0 || enc_pub || sign_pub`
     * (136 байт для этого входа). Не сходится — неверна наша раскладка, а не вектор.
     */
    @Test
    fun `подписываемые байты сходятся с вектором из раскладки Go`() {
        val байты = DeviceLinkSignature.signingBytes(sessionId, secret, encPub, signPub)
        assertEquals(
            "a58c45b77742af8860fbf8c9404e9b9d5a9d2e0dfa446768311dca341d8a634e",
            байты.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
            "раскладка подписи разошлась с серверной: сервер ответит bad_signature",
        )
    }

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
