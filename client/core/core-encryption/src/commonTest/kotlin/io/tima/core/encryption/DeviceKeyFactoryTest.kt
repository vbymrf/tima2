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
        val материал = DeviceKeyFactoryOverKodium.newDeviceKeys()

        val восстановленный = deviceIdentityFrom(материал.secret)

        assertContentEquals(материал.encryptionPub, восстановленный.encryptionPublic)
        assertContentEquals(материал.signingPub, восстановленный.signingPublic)
    }

    @Test
    fun размеры_те_что_ждёт_сервер() {
        // Сервер принимает по 32 байта и отвечает 400 bad_keys на всё остальное.
        val материал = DeviceKeyFactoryOverKodium.newDeviceKeys()
        assertEquals(32, материал.encryptionPub.size)
        assertEquals(32, материал.signingPub.size)
        assertEquals(DEVICE_SECRET_BYTES, материал.secret.size)
    }

    @Test
    fun ключи_шифрования_и_подписи_разные() {
        // Один секрет, два ключа — но именно два. Совпади они, подпись и шифрование
        // делили бы материал, чего ни один из двух алгоритмов не предполагает.
        val материал = DeviceKeyFactoryOverKodium.newDeviceKeys()
        assertFalse(материал.encryptionPub.contentEquals(материал.signingPub))
    }

    @Test
    fun каждое_порождение_даёт_новое_устройство() {
        val первое = DeviceKeyFactoryOverKodium.newDeviceKeys()
        val второе = DeviceKeyFactoryOverKodium.newDeviceKeys()

        assertFalse(первое.secret.contentEquals(второе.secret), "секреты обязаны различаться")
        assertFalse(первое.encryptionPub.contentEquals(второе.encryptionPub))
    }

    @Test
    fun секрет_не_того_размера_отвергается_внятно() {
        // Обрезанный секрет из испорченного хранилища не должен превращаться в «другое
        // устройство» — он должен быть отказом.
        val беда = assertFailsWith<IllegalArgumentException> { deviceIdentityFrom(ByteArray(16)) }
        assertTrue(беда.message.orEmpty().contains("32"), "сообщение обязано называть размер")
    }
}
