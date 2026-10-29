package io.tima.shared

import io.tima.core.encryption.DeviceEpochKeys
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.network.DeviceKeyRecord
import io.tima.core.network.EpochKeyRecord
import io.ktor.client.engine.mock.respond
import io.tima.core.network.timaDefaults
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Кольцо ключей эпох (ПЛАН-(ПС) ПС3): ключ эпохи заводится один раз на эпоху, в новую эпоху —
 * новый; отправитель заворачивает под ключ эпохи только при верной подписи устройства.
 */
class EpochKeyRingTest {

    private class Memory : EpochKeySecrets {
        val map = mutableMapOf<String, ByteArray>()
        override fun epochs() = map.keys.toList()
        override fun get(epoch: String) = map[epoch]
        override fun put(epoch: String, secret: ByteArray) { map[epoch] = secret }
        override fun remove(epoch: String) { map.remove(epoch) }
    }

    private val oct2026 = 1_791_000_000_000L // 2026-10-03 UTC
    private val nov2026 = 1_793_600_000_000L // 2026-11-02 UTC

    @Test
    fun ключ_эпохи_заводится_один_раз_и_новый_в_новую_эпоху() {
        val secrets = Memory()
        var now = oct2026
        val ring = EpochKeyRing(secrets) { now }
        assertEquals("2026-10", ring.currentEpoch())
        val first = ring.prepare()
        assertEquals(1, first.size)
        assertContentEquals(first.single(), ring.prepare().single(), "повторный запуск в ту же эпоху ключ не меняет")
        now = nov2026
        val second = ring.prepare()
        assertEquals(listOf("2026-10", "2026-11"), secrets.epochs().sorted())
        assertContentEquals(secrets.map.getValue("2026-11"), second.first(), "новый ключ — первым")
    }

    /** План КК, КК10: пока есть не забранные обёртки — ключи прошлых эпох не уничтожаются. */
    @Test
    fun прошлые_ключи_живут_пока_есть_не_забранные_обёртки() = kotlinx.coroutines.test.runTest {
        val secrets = Memory()
        var now = 1_785_000_000_000L // 2026-07
        val ring = EpochKeyRing(secrets) { now }
        ring.prepare()
        now = oct2026
        ring.prepare()
        val engine = io.ktor.client.engine.mock.MockEngine { respond("", io.ktor.http.HttpStatusCode.NoContent) }
        val client = io.ktor.client.HttpClient(engine) { timaDefaults() }
        val keys = io.tima.core.network.KeysApi(io.tima.core.network.ServerRoute.from(io.tima.core.network.RouteConfig(host = "example.com")), client) { "t" }
        val device = DeviceIdentity.generate()
        kotlin.test.assertTrue(ring.publish(keys, device, "dev-1", untaken = { 3 }))
        assertEquals(listOf("2026-07", "2026-10"), secrets.epochs().sorted(), "ключ июля уничтожен при не забранных обёртках")
        kotlin.test.assertTrue(ring.publish(keys, device, "dev-1", untaken = { null }))
        assertEquals(2, secrets.epochs().size, "не узнали — считаем, что есть")
        kotlin.test.assertTrue(ring.publish(keys, device, "dev-1", untaken = { 0 }))
        assertEquals(listOf("2026-10"), secrets.epochs().sorted())
    }

    @Test
    fun под_ключ_эпохи_только_при_верной_подписи() {
        val device = DeviceIdentity.generate()
        val pub = DeviceEpochKeys.publicOf(DeviceEpochKeys.generate())
        val good = DeviceEpochKeys.sign(device, "dev-1", "2026-10", pub)!!
        val record = DeviceKeyRecord("dev-1", device.encryptionPublic, device.signingPublic,
            epochKey = EpochKeyRecord("2026-10", pub, good))
        assertContentEquals(pub, record.wrapPub())
        val forged = record.copy(epochKey = EpochKeyRecord("2026-10", pub, DeviceEpochKeys.sign(DeviceIdentity.generate(), "dev-1", "2026-10", pub)!!))
        assertContentEquals(device.encryptionPublic, forged.wrapPub(), "чужая подпись — под основной ключ")
        assertContentEquals(device.encryptionPublic, record.copy(epochKey = null).wrapPub())
    }
}
