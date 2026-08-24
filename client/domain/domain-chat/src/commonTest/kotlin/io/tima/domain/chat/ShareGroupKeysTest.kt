package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ответ на просьбу о недостающих версиях ключа.
 *
 * Проверяется главное: отдаём только то, что у нас есть, и только запрошенное. Отдать
 * лишнюю версию значило бы выдать доступ к части истории, о которой не просили, а
 * промолчать при наличии ключа — оставить человека ждать вечно.
 */
class ShareGroupKeysTest {

    private val группа = "gggggggg-0000-0000-0000-000000000001"
    private val устройство = "dev-новичок"
    private val открытый = ByteArray(32) { 7 }

    @Test
    fun отдаются_только_имеющиеся_версии() = runTest {
        val книга = ПамятныеКлючи().apply {
            put(группа, 1, ключ(1))
            put(группа, 3, ключ(3))
        }
        val сеть = ПоддельнаяОтдача()

        val шаг = ShareGroupKeys(книга, ПростаяОбёртка, сеть)
            .поделиться(группа, устройство, открытый, versions = listOf(1, 2, 3))

        assertIs<ShareStep.Shared>(шаг)
        assertEquals(listOf(1, 3), сеть.отданные.map { it.gkVersion })
        assertEquals(устройство, сеть.кому)
    }

    @Test
    fun запрошенное_чего_у_нас_нет_молча_пропускается() = runTest {
        // Ответит другой помощник: сервер разослал просьбу всем, у кого версии есть.
        val сеть = ПоддельнаяОтдача()
        val шаг = ShareGroupKeys(ПамятныеКлючи(), ПростаяОбёртка, сеть)
            .поделиться(группа, устройство, открытый, versions = listOf(5, 6))

        assertIs<ShareStep.NothingToShare>(шаг)
        assertTrue(сеть.отданные.isEmpty(), "ушёл пустой запрос")
    }

    @Test
    fun лишнего_не_отдаём() = runTest {
        // У нас есть версия 9, но её не просили: отдать значило бы выдать доступ к части
        // истории, о которой речи не было.
        val книга = ПамятныеКлючи().apply { put(группа, 1, ключ(1)); put(группа, 9, ключ(9)) }
        val сеть = ПоддельнаяОтдача()

        ShareGroupKeys(книга, ПростаяОбёртка, сеть)
            .поделиться(группа, устройство, открытый, versions = listOf(1))

        assertEquals(listOf(1), сеть.отданные.map { it.gkVersion })
    }

    @Test
    fun обёртка_несёт_ключ_именно_той_версии() = runTest {
        val книга = ПамятныеКлючи().apply { put(группа, 4, ключ(4)) }
        val сеть = ПоддельнаяОтдача()

        ShareGroupKeys(книга, ПростаяОбёртка, сеть)
            .поделиться(группа, устройство, открытый, versions = listOf(4))

        assertContentEquals(ключ(4), сеть.отданные.single().wrapped)
    }

    @Test
    fun повторы_в_просьбе_не_дают_повторов_в_ответе() = runTest {
        val книга = ПамятныеКлючи().apply { put(группа, 2, ключ(2)) }
        val сеть = ПоддельнаяОтдача()

        ShareGroupKeys(книга, ПростаяОбёртка, сеть)
            .поделиться(группа, устройство, открытый, versions = listOf(2, 2, 2))

        assertEquals(1, сеть.отданные.size)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun ключ(n: Int) = ByteArray(32) { n.toByte() }

    /** Обёртка без криптографии: отдаёт ключ как есть — проверяем перекладывание, не шифр. */
    private object ПростаяОбёртка : GroupKeyWrapForDevice {
        override fun wrap(recipientEncryptionPub: ByteArray, key: ByteArray) =
            SharedKeyBytes(senderEphemeralPub = ByteArray(32), wrapped = key)
    }

    private class ПоддельнаяОтдача : GroupKeyShareUpload {
        val отданные = mutableListOf<SharedVersion>()
        var кому: String? = null

        override suspend fun provide(
            groupId: String,
            requesterDevice: String,
            keys: List<SharedVersion>,
        ): ShareStep {
            кому = requesterDevice
            отданные += keys
            return ShareStep.Shared(keys.size)
        }
    }

    private class ПамятныеКлючи : GroupKeyBook {
        private val хранилище = mutableMapOf<Pair<String, Int>, ByteArray>()
        override fun put(groupId: String, version: Int, key: ByteArray) {
            хранилище[groupId to version] = key
        }
        override fun key(groupId: String, version: Int): ByteArray? = хранилище[groupId to version]
        override fun latestVersion(groupId: String): Int? =
            хранилище.keys.filter { it.first == groupId }.maxOfOrNull { it.second }
        override fun versions(groupId: String): List<Int> =
            хранилище.keys.filter { it.first == groupId }.map { it.second }.sorted()
        override fun forget(groupId: String) {
            хранилище.keys.filter { it.first == groupId }.forEach { хранилище.remove(it) }
        }
    }
}
