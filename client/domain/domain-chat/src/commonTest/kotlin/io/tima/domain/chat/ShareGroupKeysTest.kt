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

    private val group = "gggggggg-0000-0000-0000-000000000001"
    private val device = "dev-новичок"
    private val open = ByteArray(32) { 7 }

    @Test
    fun отдаются_только_имеющиеся_версии() = runTest {
        val book = KeyMemorable().apply {
            put(group, 1, key(1))
            put(group, 3, key(3))
        }
        val network = FakeGiving()

        val step = ShareGroupKeys(book, WrapSimple, network)
            .share(group, device, open, versions = listOf(1, 2, 3))

        assertIs<ShareStep.Shared>(step)
        assertEquals(listOf(1, 3), network.given.map { it.gkVersion })
        assertEquals(device, network.toWhom)
    }

    @Test
    fun запрошенное_чего_у_нас_нет_молча_пропускается() = runTest {
        // Ответит другой помощник: сервер разослал просьбу всем, у кого версии есть.
        val network = FakeGiving()
        val step = ShareGroupKeys(KeyMemorable(), WrapSimple, network)
            .share(group, device, open, versions = listOf(5, 6))

        assertIs<ShareStep.NothingToShare>(step)
        assertTrue(network.given.isEmpty(), "ушёл пустой запрос")
    }

    @Test
    fun лишнего_не_отдаём() = runTest {
        // У нас есть версия 9, но её не просили: отдать значило бы выдать доступ к части
        // истории, о которой речи не было.
        val book = KeyMemorable().apply { put(group, 1, key(1)); put(group, 9, key(9)) }
        val network = FakeGiving()

        ShareGroupKeys(book, WrapSimple, network)
            .share(group, device, open, versions = listOf(1))

        assertEquals(listOf(1), network.given.map { it.gkVersion })
    }

    @Test
    fun обёртка_несёт_ключ_именно_той_версии() = runTest {
        val book = KeyMemorable().apply { put(group, 4, key(4)) }
        val network = FakeGiving()

        ShareGroupKeys(book, WrapSimple, network)
            .share(group, device, open, versions = listOf(4))

        assertContentEquals(key(4), network.given.single().wrapped)
    }

    @Test
    fun повторы_в_просьбе_не_дают_повторов_в_ответе() = runTest {
        val book = KeyMemorable().apply { put(group, 2, key(2)) }
        val network = FakeGiving()

        ShareGroupKeys(book, WrapSimple, network)
            .share(group, device, open, versions = listOf(2, 2, 2))

        assertEquals(1, network.given.size)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun key(n: Int) = ByteArray(32) { n.toByte() }

    /** Обёртка без криптографии: отдаёт ключ как есть — проверяем перекладывание, не шифр. */
    private object WrapSimple : GroupKeyWrapForDevice {
        override fun wrap(recipientEncryptionPub: ByteArray, key: ByteArray) =
            SharedKeyBytes(senderEphemeralPub = ByteArray(32), wrapped = key)
    }

    private class FakeGiving : GroupKeyShareUpload {
        val given = mutableListOf<SharedVersion>()
        var toWhom: String? = null

        override suspend fun provide(
            groupId: String,
            requesterDevice: String,
            keys: List<SharedVersion>,
        ): ShareStep {
            toWhom = requesterDevice
            given += keys
            return ShareStep.Shared(keys.size)
        }
    }

    private class KeyMemorable : GroupKeyBook {
        private val store = mutableMapOf<Pair<String, Int>, ByteArray>()
        override fun put(groupId: String, version: Int, key: ByteArray) {
            store[groupId to version] = key
        }
        override fun key(groupId: String, version: Int): ByteArray? = store[groupId to version]
        override fun latestVersion(groupId: String): Int? =
            store.keys.filter { it.first == groupId }.maxOfOrNull { it.second }
        override fun versions(groupId: String): List<Int> =
            store.keys.filter { it.first == groupId }.map { it.second }.sorted()
        override fun markSend(groupId: String, version: Int): Int = 0

        override fun forget(groupId: String) {
            store.keys.filter { it.first == groupId }.forEach { store.remove(it) }
        }
    }
}
