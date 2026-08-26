package io.tima.core.encryption

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Порождение ключей устройства и восстановление их из сохранённого секрета.
 *
 * Главное здесь — **круг**: то, что мы сохраняем, обязано полностью восстанавливать
 * оба открытых ключа. Если бы не восстанавливало, устройство после перезапуска стало
 * бы чужим само себе: сервер знает одни ключи, а приложение считает своими другие.
 */
class DeviceKeyFactoryTest {

    @Test
    fun секрет_восстанавливает_оба_ключа_целиком() {
        val material = DeviceKeyFactoryOverKodium.newDeviceKeys()

        val recovered = deviceIdentityFrom(material.secret)

        assertContentEquals(material.encryptionPub, recovered.encryptionPublic)
        assertContentEquals(material.signingPub, recovered.signingPublic)
    }

    @Test
    fun размеры_те_что_ждёт_сервер() {
        // Сервер принимает по 32 байта и отвечает 400 bad_keys на всё остальное.
        val material = DeviceKeyFactoryOverKodium.newDeviceKeys()
        assertEquals(32, material.encryptionPub.size)
        assertEquals(32, material.signingPub.size)
        assertEquals(DEVICE_SECRET_BYTES, material.secret.size)
    }

    @Test
    fun ключи_шифрования_и_подписи_разные() {
        // Один секрет, два ключа — но именно два. Совпади они, подпись и шифрование
        // делили бы материал, чего ни один из двух алгоритмов не предполагает.
        val material = DeviceKeyFactoryOverKodium.newDeviceKeys()
        assertFalse(material.encryptionPub.contentEquals(material.signingPub))
    }

    @Test
    fun каждое_порождение_даёт_новое_устройство() {
        val first = DeviceKeyFactoryOverKodium.newDeviceKeys()
        val second = DeviceKeyFactoryOverKodium.newDeviceKeys()

        assertFalse(first.secret.contentEquals(second.secret), "секреты обязаны различаться")
        assertFalse(first.encryptionPub.contentEquals(second.encryptionPub))
    }

    @Test
    fun секрет_не_того_размера_отвергается_внятно() {
        // Обрезанный секрет из испорченного хранилища не должен превращаться в «другое
        // устройство» — он должен быть отказом.
        val trouble = assertFailsWith<IllegalArgumentException> { deviceIdentityFrom(ByteArray(16)) }
        assertTrue(trouble.message.orEmpty().contains("32"), "сообщение обязано называть размер")
    }
}
