package io.tima.shared

import io.tima.core.encryption.DeviceEpochKeys
import io.tima.core.encryption.DeviceIdentity
import io.tima.core.network.DeviceKeyRecord
import io.tima.core.network.EpochKeyRecord
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
