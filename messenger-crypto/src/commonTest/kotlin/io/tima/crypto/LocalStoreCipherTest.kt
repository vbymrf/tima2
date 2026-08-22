package io.tima.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Шифрование покоя: ключ выводится из секрета устройства, поле закрывается и открывается.
 *
 * Проверок здесь ровно столько, сколько утверждений: ключ зависит от секрета и от метки,
 * закрытое поле не содержит открытого текста, чужой ключ не открывает, два вызова дают
 * разные байты.
 */
class LocalStoreCipherTest {

    private val секрет = ByteArray(32) { (it + 1).toByte() }
    private val другойСекрет = ByteArray(32) { (it + 100).toByte() }
    private val тело = "СЕКРЕТНОЕ-СОДЕРЖИМОЕ-ПЕРЕПИСКИ".encodeToByteArray()

    @Test
    fun ключ_выводится_из_секрета_устройства_и_повторяем() {
        val первый = LocalStoreCipher.keyFromDeviceSecret(секрет)
        val второй = LocalStoreCipher.keyFromDeviceSecret(секрет)

        assertEquals(LocalStoreCipher.KEY_SIZE, первый.size)
        assertContentEquals(первый, второй, "тот же секрет обязан давать тот же ключ покоя")
        assertFalse(
            первый.contentEquals(LocalStoreCipher.keyFromDeviceSecret(другойСекрет)),
            "разные устройства не должны получать один ключ",
        )
    }

    /**
     * Ключ покоя **не равен** секрету устройства.
     *
     * Проверка кажется лишней, но закрывает настоящую ошибку: «зашифруем прямо секретом»
     * выглядит рабочим и молча стирает разделение ролей — тот же материал подписывает,
     * шифрует переписку и лежит в базе.
     */
    @Test
    fun ключ_покоя_не_есть_сам_секрет() {
        assertFalse(
            LocalStoreCipher.keyFromDeviceSecret(секрет).contentEquals(секрет),
            "ключ покоя обязан быть выводом HKDF, а не самим секретом",
        )
    }

    @Test
    fun закрытое_открывается_тем_же_ключом() {
        val ключ = LocalStoreCipher.keyFromDeviceSecret(секрет)

        val закрытое = LocalStoreCipher.seal(ключ, тело).getOrThrow()
        val открытое = LocalStoreCipher.open(ключ, закрытое).getOrThrow()

        assertContentEquals(тело, открытое)
    }

    @Test
    fun в_закрытом_поле_нет_открытого_текста() {
        val ключ = LocalStoreCipher.keyFromDeviceSecret(секрет)

        val закрытое = LocalStoreCipher.seal(ключ, тело).getOrThrow()

        assertFalse(содержит(закрытое, тело), "открытый текст остался в закрытом поле")
        assertTrue(закрытое.size > тело.size, "к полю добавляются nonce и тег MAC")
    }

    @Test
    fun чужой_ключ_не_открывает() {
        val закрытое = LocalStoreCipher
            .seal(LocalStoreCipher.keyFromDeviceSecret(секрет), тело)
            .getOrThrow()

        val исход = LocalStoreCipher.open(LocalStoreCipher.keyFromDeviceSecret(другойСекрет), закрытое)

        assertTrue(исход.isFailure, "поле обязано не открыться чужим ключом, а не отдать мусор")
    }

    /**
     * Испорченный байт ловится MAC.
     *
     * Без этого «шифрование» защищало бы от чтения, но не от подмены: строка в базе,
     * которую поменяли снаружи, открылась бы как другой текст.
     */
    @Test
    fun испорченный_байт_ловится() {
        val ключ = LocalStoreCipher.keyFromDeviceSecret(секрет)
        val закрытое = LocalStoreCipher.seal(ключ, тело).getOrThrow()
        закрытое[закрытое.size - 1] = (закрытое[закрытое.size - 1] + 1).toByte()

        assertTrue(LocalStoreCipher.open(ключ, закрытое).isFailure)
    }

    @Test
    fun два_вызова_дают_разные_байты() {
        // Одинаковые байты у одинакового текста означали бы, что по базе видно, какие
        // сообщения совпадают — а это уже часть содержимого.
        val ключ = LocalStoreCipher.keyFromDeviceSecret(секрет)

        val первое = LocalStoreCipher.seal(ключ, тело).getOrThrow()
        val второе = LocalStoreCipher.seal(ключ, тело).getOrThrow()

        assertFalse(первое.contentEquals(второе), "nonce обязан быть разным при каждом вызове")
    }

    @Test
    fun секрет_не_того_размера_отвергается() {
        assertFailsWith<IllegalArgumentException> {
            LocalStoreCipher.keyFromDeviceSecret(ByteArray(16))
        }
        assertFailsWith<IllegalArgumentException> {
            LocalStoreCipher.seal(ByteArray(16), тело)
        }
    }

    /** Метка вывода печёная: её правка делает уже записанные базы нечитаемыми. */
    @Test
    fun метка_вывода_ключа_та_же() {
        assertEquals("tima/local-store/v1", LocalStoreCipher.HKDF_LABEL)
    }

    private fun содержит(где: ByteArray, что: ByteArray): Boolean {
        if (что.isEmpty() || что.size > где.size) return false
        outer@ for (i in 0..где.size - что.size) {
            for (j in что.indices) if (где[i + j] != что[j]) continue@outer
            return true
        }
        return false
    }
}
