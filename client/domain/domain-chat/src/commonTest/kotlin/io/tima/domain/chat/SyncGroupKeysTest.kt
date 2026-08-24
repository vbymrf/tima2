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

    private val группа = "gggggggg-0000-0000-0000-000000000001"

    @Test
    fun просим_только_то_чего_нет() = runTest {
        // Иначе каждая сверка тянула бы всю историю ключей группы — и по сети, и по
        // разворачиванию, которое считается на каждую обёртку.
        val сеть = ПоддельныеОбёртки(GroupKeyWrapsStep.Wraps(emptyList(), currentVersion = 5))
        val книга = ПамятныеКлючи().apply { put(группа, 4, ByteArray(32)) }

        SyncGroupKeys(сеть, ВсегдаРазворачивает, книга).обновить(группа)

        assertEquals(4, сеть.спрошенаВерсия, "спросили не с той версии")
    }

    @Test
    fun без_своих_ключей_просим_всё() = runTest {
        val сеть = ПоддельныеОбёртки(GroupKeyWrapsStep.Wraps(emptyList(), currentVersion = 0))
        SyncGroupKeys(сеть, ВсегдаРазворачивает, ПамятныеКлючи()).обновить(группа)
        assertEquals(0, сеть.спрошенаВерсия)
    }

    @Test
    fun обёртки_разворачиваются_и_ложатся_в_книгу() = runTest {
        val сеть = ПоддельныеОбёртки(
            GroupKeyWrapsStep.Wraps(
                listOf(обёртка(5), обёртка(6)),
                currentVersion = 6,
            ),
        )
        val книга = ПамятныеКлючи()

        val шаг = SyncGroupKeys(сеть, ВсегдаРазворачивает, книга).обновить(группа)

        assertIs<SyncKeysStep.Synced>(шаг)
        assertEquals(2, шаг.добавлено)
        assertEquals(0, шаг.неоткрытых)
        assertEquals(listOf(5, 6), книга.versions(группа))
        assertContentEquals(ключПоВерсии(5), книга.key(группа, 5))
    }

    @Test
    fun неоткрывшаяся_обёртка_не_отменяет_остальные() = runTest {
        // Обёртка могла быть испорчена или адресована прежнему ключу устройства. Уронить
        // из-за неё разбор значило бы потерять и те ключи, что открылись, — то есть
        // оставить группу нечитаемой целиком вместо одного сообщения.
        val сеть = ПоддельныеОбёртки(
            GroupKeyWrapsStep.Wraps(listOf(обёртка(1), обёртка(2), обёртка(3)), currentVersion = 3),
        )
        val книга = ПамятныеКлючи()

        val шаг = SyncGroupKeys(сеть, РазворачиваетКромеВторой, книга).обновить(группа)

        assertIs<SyncKeysStep.Synced>(шаг)
        assertEquals(2, шаг.добавлено)
        assertEquals(1, шаг.неоткрытых)
        assertEquals(listOf(1, 3), книга.versions(группа))
        assertNull(книга.key(группа, 2))
    }

    @Test
    fun текущая_версия_группы_берётся_серверная() = runTest {
        // Нас могли добавить после ротации: сервер знает версию 9, а нам выдал только 8.
        // Отправляющему нужна серверная — зашифровав своей, он напишет так, что новые
        // участники не прочтут.
        val сеть = ПоддельныеОбёртки(GroupKeyWrapsStep.Wraps(listOf(обёртка(8)), currentVersion = 9))
        val шаг = SyncGroupKeys(сеть, ВсегдаРазворачивает, ПамятныеКлючи()).обновить(группа)
        assertEquals(9, (шаг as SyncKeysStep.Synced).текущаяВерсия)
    }

    @Test
    fun отказ_и_обрыв_проходят_наружу_как_есть() = runTest {
        val обрыв = SyncGroupKeys(
            ПоддельныеОбёртки(GroupKeyWrapsStep.Offline(retryAfterMs = 3_000)),
            ВсегдаРазворачивает,
            ПамятныеКлючи(),
        ).обновить(группа)
        assertIs<SyncKeysStep.Offline>(обрыв)
        assertEquals(3_000, обрыв.retryAfterMs)

        val отказ = SyncGroupKeys(
            ПоддельныеОбёртки(GroupKeyWrapsStep.Refused("not_group_member")),
            ВсегдаРазворачивает,
            ПамятныеКлючи(),
        ).обновить(группа)
        assertIs<SyncKeysStep.Refused>(отказ)
        assertEquals("not_group_member", отказ.reason)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun обёртка(версия: Int) =
        WrappedGroupKeyInfo(версия, senderEphemeralPub = ByteArray(32), wrapped = байтыВерсии(версия))

    private fun байтыВерсии(версия: Int) = ByteArray(48) { версия.toByte() }
    private fun ключПоВерсии(версия: Int) = ByteArray(32) { версия.toByte() }

    private class ПоддельныеОбёртки(private val ответ: GroupKeyWrapsStep) : GroupKeyWraps {
        var спрошенаВерсия: Int = -1
            private set

        override suspend fun mine(groupId: String, sinceVersion: Int): GroupKeyWrapsStep {
            спрошенаВерсия = sinceVersion
            return ответ
        }
    }

    /** Разворачивает всё: обёртка версии N даёт ключ версии N. */
    private object ВсегдаРазворачивает : GroupKeyUnwrap {
        override fun unwrap(senderEphemeralPub: ByteArray, wrapped: ByteArray): ByteArray? =
            ByteArray(32) { wrapped[0] }
    }

    /** Не разворачивает обёртку второй версии — как испорченную. */
    private object РазворачиваетКромеВторой : GroupKeyUnwrap {
        override fun unwrap(senderEphemeralPub: ByteArray, wrapped: ByteArray): ByteArray? =
            if (wrapped[0].toInt() == 2) null else ByteArray(32) { wrapped[0] }
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

        override fun отметитьОтправку(groupId: String, version: Int): Int = 0

        override fun forget(groupId: String) {
            хранилище.keys.filter { it.first == groupId }.forEach { хранилище.remove(it) }
        }
    }
}
