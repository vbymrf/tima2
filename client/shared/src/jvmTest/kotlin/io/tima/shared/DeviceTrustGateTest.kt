package io.tima.shared

import io.kodium.Kodium
import io.tima.core.network.DeviceKeyRecord
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.SigningKeyRecord
import io.tima.crypto.DeviceTrust
import io.tima.domain.chat.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Отбор устройств собеседника (ДУ3, беда «вор SIM читает новые сообщения»): в строгом режиме
 * устройству вора ни ключ сообщения, ни ключ группы не упаковываются.
 */
class DeviceTrustGateTest {

    private class Memory : Settings {
        val values = MutableStateFlow<Map<String, String>>(emptyMap())
        override fun all(): Flow<Map<String, String>> = values
        override suspend fun put(name: String, value: String) {
            values.value = values.value + (name to value)
        }
    }

    private val identity = Kodium.generateKeyPair()
    private val ask = Kodium.generateKeyPair()
    private val identityPub = identity.getPublicKey().signingKey
    private val askPub = ask.getPublicKey().signingKey
    private val askRecord = SigningKeyRecord("ask-1", askPub, DeviceTrust.sign(identity, DeviceTrust.askCertBytes(askPub))!!)

    private fun device(id: String, certified: Boolean): DeviceKeyRecord {
        val enc = ByteArray(32) { (id.hashCode() + it).toByte() }
        val sig = ByteArray(32) { (id.hashCode() - it).toByte() }
        return if (certified) {
            DeviceKeyRecord(id, enc, sig, "ask", "ask-1", DeviceTrust.sign(ask, DeviceTrust.deviceCertBytes(enc, sig))!!)
        } else {
            DeviceKeyRecord(id, enc, sig)
        }
    }

    private val owner = device("телефон-хозяина", certified = true)
    private val thief = device("телефон-вора", certified = false)

    private fun answer(mode: String, idPub: ByteArray? = identityPub) =
        DeviceKeysResult.Devices(listOf(owner, thief), idPub, listOf(askRecord), mode)

    @Test
    fun строгий_режим_отсекает_устройство_вора() = runTest {
        val gate = DeviceTrustGate(Memory())
        assertEquals(listOf("телефон-хозяина"), gate.admit("u-1", answer("require")).map { it.deviceId })
    }

    @Test
    fun режим_записи_и_выключенный_пускают_всех() = runTest {
        val gate = DeviceTrustGate(Memory())
        assertEquals(2, gate.admit("u-1", answer("record")).size)
        assertEquals(2, gate.admit("u-1", answer("off")).size)
    }

    @Test
    fun подменённый_ключ_личности_не_доверяется_никому() = runTest {
        val gate = DeviceTrustGate(Memory())
        gate.admit("u-1", answer("require"))
        // Сервер подменил ключ личности на свой и заверил им устройство вора.
        val fake = Kodium.generateKeyPair()
        val fakePub = fake.getPublicKey().signingKey
        val forged = DeviceKeyRecord(thief.deviceId, thief.encryptionPub, thief.signingPub, "identity", null,
            DeviceTrust.sign(fake, DeviceTrust.deviceCertBytes(thief.encryptionPub, thief.signingPub))!!)
        val lie = DeviceKeysResult.Devices(listOf(owner, forged), fakePub, emptyList(), "require")
        assertEquals(emptyList(), gate.admit("u-1", lie).map { it.deviceId })
    }

    @Test
    fun аккаунт_без_фразы_в_строгом_режиме_не_получает_ничего() = runTest {
        val gate = DeviceTrustGate(Memory())
        assertEquals(emptyList(), gate.admit("u-2", answer("require", idPub = null)).map { it.deviceId })
    }
}
