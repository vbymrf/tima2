package io.tima.core.network

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Код заверения уже подключённого устройства (Р32): сборка и разбор сходятся, мусор — `null`. */
class CertifyQrTest {

    @Test
    fun код_собирается_и_разбирается() {
        val enc = ByteArray(32) { it.toByte() }
        val sig = ByteArray(32) { (it * 3).toByte() }
        val code = CertifyQr.payload("d-1", enc, sig)
        assertTrue(CertifyQr.isCertify(code))
        val read = CertifyQr.parse(code)!!
        assertEquals("d-1", read.deviceId)
        assertContentEquals(enc, read.encryptionPub)
        assertContentEquals(sig, read.signingPub)
    }

    @Test
    fun чужой_или_битый_код_не_разбирается() {
        assertNull(CertifyQr.parse("tima://transfer/v1?code=abc"))
        assertNull(CertifyQr.parse("tima://certify/v1?device=d-1&enc=AAA&sig=BBB"))
        assertNull(CertifyQr.parse("tima://certify/v1?enc=AAA"))
    }
}
