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

    private val группа = "gggggggg-0000-0000-0000-000000000001"

    @Test
    fun шифруем_последней_известной_нам_версией() = runTest {
        // Не серверной: серверная могла уйти вперёд, а ключа от неё у нас ещё нет.
        val книга = ПамятныеКлючи().apply {
            put(группа, 4, ByteArray(32) { 4 })
            put(группа, 7, ByteArray(32) { 7 })
        }
        val сеть = ПоддельныйТранспорт()

        случай(книга, сеть).отправить(группа, "привет")

        assertEquals(7, сеть.версия)
    }

    @Test
    fun без_ключа_до_сети_не_доходим() = runTest {
        // Только что созданная группа до первой ротации: это не сеть и не лечится повтором.
        val сеть = ПоддельныйТранспорт()
        val шаг = случай(ПамятныеКлючи(), сеть).отправить(группа, "привет")

        assertIs<SendGroupStep.NoKey>(шаг)
        assertEquals(0, сеть.попыток)
    }

    @Test
    fun счётчик_растёт_только_после_успеха() = runTest {
        // Считать попытки значило бы ротировать ключ из-за обрывов связи, то есть
        // наказывать за плохую сеть выдачей обёрток всем устройствам.
        val книга = ключи()
        val сеть = ПоддельныйТранспорт(ответ = GroupSendStep.Offline(1_000))

        случай(книга, сеть).отправить(группа, "привет")

        assertEquals(0, книга.счёт(группа, 1))
    }

    @Test
    fun повтор_не_увеличивает_счётчик() = runTest {
        // Под этой версией отправлено одно сообщение, а не два: сервер опознал дубль.
        val книга = ключи()
        val сеть = ПоддельныйТранспорт(ответ = GroupSendStep.Duplicate(messageId = 42))

        val шаг = случай(книга, сеть).отправить(группа, "привет")

        assertIs<SendGroupStep.Sent>(шаг)
        assertFalse(шаг.ротацияЗапущена)
        assertEquals(0, книга.счёт(группа, 1))
    }

    @Test
    fun порог_запускает_ротацию_и_только_на_нём() = runTest {
        val книга = ключи().apply { счётчик[группа to 1] = SendGroupMessage.ПОРОГ_РОТАЦИИ - 2 }
        val ротатор = ПоддельнаяРотация()
        val случай = случай(книга, ПоддельныйТранспорт(), ротатор)

        val доПорога = случай.отправить(группа, "раз")
        assertFalse((доПорога as SendGroupStep.Sent).ротацияЗапущена, "ротация раньше порога")
        assertTrue(ротатор.вызовы.isEmpty())

        val наПороге = случай.отправить(группа, "два")
        assertTrue((наПороге as SendGroupStep.Sent).ротацияЗапущена)
        assertEquals(listOf(группа), ротатор.вызовы)
    }

    @Test
    fun неизвестная_серверу_версия_это_повод_за_ключами() = runTest {
        // Не повод повторять отправку: пока ключи не сойдутся, повтор даст то же самое.
        val шаг = случай(ключи(), ПоддельныйТранспорт(ответ = GroupSendStep.UnknownKeyVersion))
            .отправить(группа, "привет")
        assertIs<SendGroupStep.NeedKeys>(шаг)
    }

    @Test
    fun пустое_сообщение_до_сети_не_доходит() = runTest {
        val сеть = ПоддельныйТранспорт()
        assertIs<SendGroupStep.Empty>(случай(ключи(), сеть).отправить(группа, "   "))
        assertEquals(0, сеть.попыток)
    }

    // ── подделки ────────────────────────────────────────────────────────────

    private fun ключи() = ПамятныеКлючи().apply { put(группа, 1, ByteArray(32) { 1 }) }

    private fun случай(
        книга: ПамятныеКлючи,
        сеть: ПоддельныйТранспорт,
        ротатор: GroupKeyRotator = ПоддельнаяРотация(),
    ) = SendGroupMessage(
        keys = книга,
        sealer = { _, _, _, текст, _ ->
            SealedGroupBytes(payload = текст.encodeToByteArray(), signature = ByteArray(64))
        },
        transport = сеть,
        dedup = DedupKeys { "d-1" },
        rotator = ротатор,
        nowMs = { 1_750_000_000_000 },
    )

    private class ПоддельнаяРотация : GroupKeyRotator {
        val вызовы = mutableListOf<String>()
        override suspend fun ротировать(groupId: String): RotateStep {
            вызовы += groupId
            return RotateStep.Rotated
        }
    }

    private class ПоддельныйТранспорт(
        private val ответ: GroupSendStep = GroupSendStep.Sent(messageId = 1),
    ) : GroupTransport {
        var версия: Int = -1
            private set
        var попыток: Int = 0
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
            версия = gkVersion
            попыток++
            return ответ
        }
    }

    private class ПамятныеКлючи : GroupKeyBook {
        private val хранилище = mutableMapOf<Pair<String, Int>, ByteArray>()
        val счётчик = mutableMapOf<Pair<String, Int>, Int>()

        fun счёт(groupId: String, version: Int) = счётчик[groupId to version] ?: 0

        override fun put(groupId: String, version: Int, key: ByteArray) {
            хранилище[groupId to version] = key
        }
        override fun key(groupId: String, version: Int): ByteArray? = хранилище[groupId to version]
        override fun latestVersion(groupId: String): Int? =
            хранилище.keys.filter { it.first == groupId }.maxOfOrNull { it.second }
        override fun versions(groupId: String): List<Int> =
            хранилище.keys.filter { it.first == groupId }.map { it.second }.sorted()
        override fun отметитьОтправку(groupId: String, version: Int): Int {
            val n = счёт(groupId, version) + 1
            счётчик[groupId to version] = n
            return n
        }
        override fun forget(groupId: String) {
            хранилище.keys.filter { it.first == groupId }.forEach { хранилище.remove(it) }
        }
    }
}
