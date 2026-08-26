package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Отправка в группу и порог счётчика.
 *
 * Проверяется не «дошло ли», а решения: какой версией шифруем, когда растёт счётчик и
 * когда он приводит к ротации. Ошибка в любом из трёх стоит либо нечитаемых сообщений,
 * либо лишних фан-аутов обёрток на все устройства группы.
 */
class SendGroupMessageTest {

    private val group = "gggggggg-0000-0000-0000-000000000001"

    @Test
    fun шифруем_последней_известной_нам_версией() = runTest {
        // Не серверной: серверная могла уйти вперёд, а ключа от неё у нас ещё нет.
        val book = KeyMemorable().apply {
            put(group, 4, ByteArray(32) { 4 })
            put(group, 7, ByteArray(32) { 7 })
        }
        val network = FakeTransport()

        case(book, network).send(group, "привет")

        assertEquals(7, network.version)
    }

    @Test
    fun без_ключа_до_сети_не_доходим() = runTest {
        // Только что созданная группа до первой ротации: это не сеть и не лечится повтором.
        val network = FakeTransport()
        val step = case(KeyMemorable(), network).send(group, "привет")

        assertIs<SendGroupStep.NoKey>(step)
        assertEquals(0, network.attempts)
    }

    @Test
    fun счётчик_растёт_только_после_успеха() = runTest {
        // Считать попытки значило бы ротировать ключ из-за обрывов связи, то есть
        // наказывать за плохую сеть выдачей обёрток всем устройствам.
        val book = keys()
        val network = FakeTransport(answer = GroupSendStep.Offline(1_000))

        case(book, network).send(group, "привет")

        assertEquals(0, book.count(group, 1))
    }

    @Test
    fun повтор_не_увеличивает_счётчик() = runTest {
        // Под этой версией отправлено одно сообщение, а не два: сервер опознал дубль.
        val book = keys()
        val network = FakeTransport(answer = GroupSendStep.Duplicate(messageId = 42))

        val step = case(book, network).send(group, "привет")

        assertIs<SendGroupStep.Sent>(step)
        assertFalse(step.launchedRotation)
        assertEquals(0, book.count(group, 1))
    }

    @Test
    fun порог_запускает_ротацию_и_только_на_нём() = runTest {
        val book = keys().apply { counter[group to 1] = SendGroupMessage.ROTATION_THRESHOLD - 2 }
        val rotator = FakeRotation()
        val case = case(book, FakeTransport(), rotator)

        val untilThreshold = case.send(group, "раз")
        assertFalse((untilThreshold as SendGroupStep.Sent).launchedRotation, "ротация раньше порога")
        assertTrue(rotator.calls.isEmpty())

        val onThreshold = case.send(group, "два")
        assertTrue((onThreshold as SendGroupStep.Sent).launchedRotation)
        assertEquals(listOf(group), rotator.calls)
    }

    @Test
    fun неизвестная_серверу_версия_это_повод_за_ключами() = runTest {
        // Не повод повторять отправку: пока ключи не сойдутся, повтор даст то же самое.
        val step = case(keys(), FakeTransport(answer = GroupSendStep.UnknownKeyVersion))
            .send(group, "привет")
        assertIs<SendGroupStep.NeedKeys>(step)
    }

    @Test
    fun пустое_сообщение_до_сети_не_доходит() = runTest {
        val network = FakeTransport()
        assertIs<SendGroupStep.Empty>(case(keys(), network).send(group, "   "))
        assertEquals(0, network.attempts)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun keys() = KeyMemorable().apply { put(group, 1, ByteArray(32) { 1 }) }

    private fun case(
        book: KeyMemorable,
        network: FakeTransport,
        rotator: GroupKeyRotator = FakeRotation(),
    ) = SendGroupMessage(
        keys = book,
        sealer = { _, _, _, text, _ ->
            SealedGroupBytes(payload = text.encodeToByteArray(), signature = ByteArray(64))
        },
        transport = network,
        dedup = DedupKeys { "d-1" },
        rotator = rotator,
        nowMs = { 1_750_000_000_000 },
    )

    private class FakeRotation : GroupKeyRotator {
        val calls = mutableListOf<String>()
        override suspend fun rotate(groupId: String): RotateStep {
            calls += groupId
            return RotateStep.Rotated
        }
    }

    private class FakeTransport(
        private val answer: GroupSendStep = GroupSendStep.Sent(messageId = 1),
    ) : GroupTransport {
        var version: Int = -1
            private set
        var attempts: Int = 0
            private set

        override suspend fun send(
            groupId: String,
            clientMsgId: String,
            kind: Int,
            gkVersion: Int,
            payload: ByteArray,
            signature: ByteArray,
            createdAtUnixMs: Long,
        ): GroupSendStep {
            version = gkVersion
            attempts++
            return answer
        }
    }

    private class KeyMemorable : GroupKeyBook {
        private val store = mutableMapOf<Pair<String, Int>, ByteArray>()
        val counter = mutableMapOf<Pair<String, Int>, Int>()

        fun count(groupId: String, version: Int) = counter[groupId to version] ?: 0

        override fun put(groupId: String, version: Int, key: ByteArray) {
            store[groupId to version] = key
        }
        override fun key(groupId: String, version: Int): ByteArray? = store[groupId to version]
        override fun latestVersion(groupId: String): Int? =
            store.keys.filter { it.first == groupId }.maxOfOrNull { it.second }
        override fun versions(groupId: String): List<Int> =
            store.keys.filter { it.first == groupId }.map { it.second }.sorted()
        override fun markSend(groupId: String, version: Int): Int {
            val n = count(groupId, version) + 1
            counter[groupId to version] = n
            return n
        }
        override fun forget(groupId: String) {
            store.keys.filter { it.first == groupId }.forEach { store.remove(it) }
        }
    }
}
