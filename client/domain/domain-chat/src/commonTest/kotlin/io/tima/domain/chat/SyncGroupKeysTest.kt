package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Сверка групповых ключей: что просим у сервера и что кладём себе.
 *
 * Проверяется не «вызвался ли метод», а три решения, каждое из которых стоит читаемости
 * группы: спрашиваем только недостающее, неоткрывшаяся обёртка не отменяет остальные, и
 * текущая версия группы берётся серверная, а не наша.
 */
class SyncGroupKeysTest {

    private val group = "gggggggg-0000-0000-0000-000000000001"

    @Test
    fun просим_только_то_чего_нет() = runTest {
        // Иначе каждая сверка тянула бы всю историю ключей группы — и по сети, и по
        // разворачиванию, которое считается на каждую обёртку.
        val network = FakeWraps(GroupKeyWrapsStep.Wraps(emptyList(), currentVersion = 5))
        val book = KeyMemorable().apply { put(group, 4, ByteArray(32)) }

        SyncGroupKeys(network, UnwrapAlways, book).refresh(group)

        assertEquals(4, network.versionAsked, "спросили не с той версии")
    }

    @Test
    fun без_своих_ключей_просим_всё() = runTest {
        val network = FakeWraps(GroupKeyWrapsStep.Wraps(emptyList(), currentVersion = 0))
        SyncGroupKeys(network, UnwrapAlways, KeyMemorable()).refresh(group)
        assertEquals(0, network.versionAsked)
    }

    @Test
    fun обёртки_разворачиваются_и_ложатся_в_книгу() = runTest {
        val network = FakeWraps(
            GroupKeyWrapsStep.Wraps(
                listOf(wrap(5), wrap(6)),
                currentVersion = 6,
            ),
        )
        val book = KeyMemorable()

        val step = SyncGroupKeys(network, UnwrapAlways, book).refresh(group)

        assertIs<SyncKeysStep.Synced>(step)
        assertEquals(2, step.added)
        assertEquals(0, step.unopened)
        assertEquals(listOf(5, 6), book.versions(group))
        assertContentEquals(keyByVersion(5), book.key(group, 5))
    }

    @Test
    fun неоткрывшаяся_обёртка_не_отменяет_остальные() = runTest {
        // Обёртка могла быть испорчена или адресована прежнему ключу устройства. Уронить
        // из-за неё разбор значило бы потерять и те ключи, что открылись, — то есть
        // оставить группу нечитаемой целиком вместо одного сообщения.
        val network = FakeWraps(
            GroupKeyWrapsStep.Wraps(listOf(wrap(1), wrap(2), wrap(3)), currentVersion = 3),
        )
        val book = KeyMemorable()

        val step = SyncGroupKeys(network, UnwrapExceptSecond, book).refresh(group)

        assertIs<SyncKeysStep.Synced>(step)
        assertEquals(2, step.added)
        assertEquals(1, step.unopened)
        assertEquals(listOf(1, 3), book.versions(group))
        assertNull(book.key(group, 2))
    }

    @Test
    fun текущая_версия_группы_берётся_серверная() = runTest {
        // Нас могли добавить после ротации: сервер знает версию 9, а нам выдал только 8.
        // Отправляющему нужна серверная — зашифровав своей, он напишет так, что новые
        // участники не прочтут.
        val network = FakeWraps(GroupKeyWrapsStep.Wraps(listOf(wrap(8)), currentVersion = 9))
        val step = SyncGroupKeys(network, UnwrapAlways, KeyMemorable()).refresh(group)
        assertEquals(9, (step as SyncKeysStep.Synced).currentVersion)
    }

    @Test
    fun отказ_и_обрыв_проходят_наружу_как_есть() = runTest {
        val breakage = SyncGroupKeys(
            FakeWraps(GroupKeyWrapsStep.Offline(retryAfterMs = 3_000)),
            UnwrapAlways,
            KeyMemorable(),
        ).refresh(group)
        assertIs<SyncKeysStep.Offline>(breakage)
        assertEquals(3_000, breakage.retryAfterMs)

        val refusal = SyncGroupKeys(
            FakeWraps(GroupKeyWrapsStep.Refused("not_group_member")),
            UnwrapAlways,
            KeyMemorable(),
        ).refresh(group)
        assertIs<SyncKeysStep.Refused>(refusal)
        assertEquals("not_group_member", refusal.reason)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun wrap(version: Int) =
        WrappedGroupKeyInfo(version, senderEphemeralPub = ByteArray(32), wrapped = versionBytes(version))

    private fun versionBytes(version: Int) = ByteArray(48) { version.toByte() }
    private fun keyByVersion(version: Int) = ByteArray(32) { version.toByte() }

    private class FakeWraps(private val answer: GroupKeyWrapsStep) : GroupKeyWraps {
        var versionAsked: Int = -1
            private set

        override suspend fun mine(groupId: String, sinceVersion: Int): GroupKeyWrapsStep {
            versionAsked = sinceVersion
            return answer
        }
    }

    /** Разворачивает всё: обёртка версии N даёт ключ версии N. */
    private object UnwrapAlways : GroupKeyUnwrap {
        override fun unwrap(senderEphemeralPub: ByteArray, wrapped: ByteArray): ByteArray? =
            ByteArray(32) { wrapped[0] }
    }

    /** Не разворачивает обёртку второй версии — как испорченную. */
    private object UnwrapExceptSecond : GroupKeyUnwrap {
        override fun unwrap(senderEphemeralPub: ByteArray, wrapped: ByteArray): ByteArray? =
            if (wrapped[0].toInt() == 2) null else ByteArray(32) { wrapped[0] }
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
