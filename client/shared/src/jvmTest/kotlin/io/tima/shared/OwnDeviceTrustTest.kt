package io.tima.shared

import io.kodium.Kodium
import io.tima.core.network.DeviceKeyRecord
import io.tima.core.network.DeviceKeysResult
import io.tima.core.network.SigningKeyRecord
import io.tima.crypto.DeviceTrust
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Событие «Это устройство не заверено» (отчёт QMTG, решение заказчика 2026-10-06): только в
 * «требовать», только при ключе личности, и проверка заверения — своя, а не слово сервера.
 */
class OwnDeviceTrustTest {

    private val identity = Kodium.generateKeyPair()
    private val ask = Kodium.generateKeyPair()
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

    private val phone = device("телефон", certified = true)
    private val pc = device("компьютер", certified = false)

    private fun answer(mode: String, idPub: ByteArray? = identity.getPublicKey().signingKey) =
        DeviceKeysResult.Devices(listOf(phone, pc), idPub, listOf(askRecord), mode)

    @Test
    fun в_требовать_незаверенное_просит_заверить_а_заверенное_нет() {
        assertEquals(true, OwnDeviceTrust.needsCertify(answer("require"), pc.signingPub))
        assertEquals(false, OwnDeviceTrust.needsCertify(answer("require"), phone.signingPub))
    }

    @Test
    fun в_записывать_и_без_ключа_личности_не_тревожим() {
        assertEquals(false, OwnDeviceTrust.needsCertify(answer("record"), pc.signingPub))
        assertEquals(false, OwnDeviceTrust.needsCertify(answer("require", idPub = null), pc.signingPub))
    }

    @Test
    fun своего_устройства_нет_в_ответе_не_знаем() {
        assertEquals(null, OwnDeviceTrust.needsCertify(answer("require"), ByteArray(32)))
    }

    @Test
    fun поле_заверено_от_сервера_без_верной_подписи_не_считается() {
        val forged = pc.copy(certBy = "ask", certAskId = "ask-1", certSig = ByteArray(64))
        val a = DeviceKeysResult.Devices(listOf(phone, forged), identity.getPublicKey().signingKey, listOf(askRecord), "require")
        assertEquals(true, OwnDeviceTrust.needsCertify(a, forged.signingPub))
    }
}
