package io.tima.crypto

import io.kodium.Kodium
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Доверие к устройствам. Строки байт — те же, что проверяет `TestDeviceTrustBytes` на Go:
 * расхождение значит подпись клиента, которую сервер не примет.
 */
class DeviceTrustTest {

    private val enc = ByteArray(32) { it.toByte() }
    private val sig = ByteArray(32) { (255 - it).toByte() }

    @Test
    fun байты_совпадают_с_сервером() {
        assertEquals("tima.ask.v1|AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", DeviceTrust.askCertBytes(enc).decodeToString())
        assertEquals(
            "tima.device.v1|AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8|__79_Pv6-fj39vX08_Lx8O_u7ezr6uno5-bl5OPi4eA",
            DeviceTrust.deviceCertBytes(enc, sig).decodeToString(),
        )
        assertEquals(
            "tima.ask-revoke.v1|6f1c2b4e-0000-4000-8000-000000000001",
            DeviceTrust.askRevokeBytes("6f1c2b4e-0000-4000-8000-000000000001").decodeToString(),
        )
    }

    @Test
    fun цепочка_личность_кпу_устройство() {
        val identity = Kodium.generateKeyPair()
        val ask = Kodium.generateKeyPair()
        val identityPub = identity.getPublicKey().signingKey
        val askPub = ask.getPublicKey().signingKey
        val askCert = SigningKeyCertificate("ask-1", askPub, DeviceTrust.sign(identity, DeviceTrust.askCertBytes(askPub))!!)
        val cert = DeviceCertificate(DeviceTrust.BY_ASK, "ask-1", DeviceTrust.sign(ask, DeviceTrust.deviceCertBytes(enc, sig))!!)
        assertTrue(DeviceTrust.deviceTrusted(identityPub, listOf(askCert), enc, sig, cert))

        // КПУ, заверенный чужой личностью, — не наш.
        val stranger = Kodium.generateKeyPair()
        val forged = SigningKeyCertificate("ask-1", askPub, DeviceTrust.sign(stranger, DeviceTrust.askCertBytes(askPub))!!)
        assertFalse(DeviceTrust.deviceTrusted(identityPub, listOf(forged), enc, sig, cert))
        // КПУ отозван — сервер его не отдаёт.
        assertFalse(DeviceTrust.deviceTrusted(identityPub, emptyList(), enc, sig, cert))
        // Свидетельство чужих ключей к этому устройству не подходит.
        assertFalse(DeviceTrust.deviceTrusted(identityPub, listOf(askCert), sig, enc, cert))
        // Без свидетельства — не заверено.
        assertFalse(DeviceTrust.deviceTrusted(identityPub, listOf(askCert), enc, sig, null))
    }

    @Test
    fun устройство_заверенное_фразой() {
        val identity = Kodium.generateKeyPair()
        val identityPub = identity.getPublicKey().signingKey
        val cert = DeviceCertificate(DeviceTrust.BY_IDENTITY, null, DeviceTrust.sign(identity, DeviceTrust.deviceCertBytes(enc, sig))!!)
        assertTrue(DeviceTrust.deviceTrusted(identityPub, emptyList(), enc, sig, cert))
        assertFalse(DeviceTrust.deviceTrusted(Kodium.generateKeyPair().getPublicKey().signingKey, emptyList(), enc, sig, cert))
    }
}
