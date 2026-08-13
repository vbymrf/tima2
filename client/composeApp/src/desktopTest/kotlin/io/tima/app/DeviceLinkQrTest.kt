package io.tima.app

import io.tima.app.api.parseDeviceLinkQr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceLinkQrTest {

    private val name64 = "0J3QvtGD0YLQsdGD0Lo" // base64url("Ноутбук") без паддинга

    private fun payload(
        sessionId: String = "aaaaaaaa-0000-0000-0000-00000000c4a7",
        secret: String = "s3cr3t",
        enc: String = "encpubkey",
        sig: String = "sigpubkey",
        name: String = name64,
    ) = "tima://link/v1?session_id=$sessionId&secret=$secret&encryption_key=$enc&signing_key=$sig&name=$name"

    @Test
    fun `разбирает корректный QR`() {
        val qr = parseDeviceLinkQr(payload())
        assertEquals("aaaaaaaa-0000-0000-0000-00000000c4a7", qr?.sessionId)
        assertEquals("s3cr3t", qr?.secret)
        assertEquals("encpubkey", qr?.encryptionPubB64)
        assertEquals("sigpubkey", qr?.signingPubB64)
        assertEquals("Ноутбук", qr?.deviceName)
    }

    @Test
    fun `не наш формат - null`() {
        assertNull(parseDeviceLinkQr("https://example.com/whatever"))
        assertNull(parseDeviceLinkQr(""))
    }

    @Test
    fun `не хватает поля - null, а не падение`() {
        val broken = "tima://link/v1?session_id=x&secret=y&encryption_key=z"
        assertNull(parseDeviceLinkQr(broken))
    }

    @Test
    fun `пробелы вокруг payload не мешают разбору`() {
        assertEquals("s3cr3t", parseDeviceLinkQr("  ${payload()}  ")?.secret)
    }
}
