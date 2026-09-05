package io.tima.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Синхронизация книги (ПЛАН-КОНТАКТОВ.md, Д3 и Д4).
 *
 * Главное здесь — что делает сценарий, когда что-то не удалось: отказ в разрешении,
 * отсутствие книги у платформы и обрыв сети — три разных исхода, и ни один из них не
 * должен опустошать книгу. Пустая книга после отказа сети выглядит как «все ваши
 * контакты пропали».
 */
class SyncBookTest {

    private class ПамятнаяКнига : Book {
        val строки = MutableStateFlow<List<BookEntry>>(emptyList())
        var сверок = 0

        override fun list(): Flow<List<BookEntry>> = строки
        override fun sections(): Flow<List<String>> = MutableStateFlow(emptyList())

        override suspend fun fromPhoneBook(entries: List<PhoneBookEntry>) {
            val было = строки.value.associateBy { it.phone }
            val стало = LinkedHashMap(было)
            entries.forEach { e ->
                val прежний = было[e.phone]
                стало[e.phone] = (прежний ?: BookEntry(e.phone)).copy(namePhone = e.name)
            }
            строки.value = стало.values.toList()
        }

        override suspend fun addManually(phone: String, name: String?, section: String) {
            строки.value = строки.value + BookEntry(phone, nameOwn = name, section = section, manual = true)
        }

        override suspend fun rename(phone: String, name: String?) = Unit
        override suspend fun moveTo(phone: String, section: String) = Unit
        override suspend fun hide(phone: String) = Unit

        override suspend fun matched(found: Map<String, String?>) {
            сверок++
            строки.value = строки.value.map { row ->
                if (found.containsKey(row.phone)) row.copy(userId = found[row.phone]) else row
            }
        }

        override suspend fun addSection(name: String) = Unit
        override suspend fun removeSection(name: String) = Unit
    }

    private val книгаТелефона = listOf(
        PhoneBookEntry("+79160001122", "Борис"),
        PhoneBookEntry("+79035554433", "Анна"),
    )

    @Test
    fun прочитанное_ложится_в_книгу_и_сверяется() = runTest {
        val книга = ПамятнаяКнига()
        val итог = SyncBook(
            phones = { PhoneBookRead.Entries(книгаТелефона) },
            book = книга,
            discovery = { phones -> phones.associateWith { if (it.endsWith("1122")) "u-1" else null } },
        ).run()

        assertEquals(SyncStep.Done(read = 2, matched = 1), итог)
        assertTrue(книга.строки.value.single { it.phone == "+79160001122" }.inTima)
        assertTrue(!книга.строки.value.single { it.phone == "+79035554433" }.inTima)
    }

    @Test
    fun без_сети_книга_остаётся_прежней() = runTest {
        val книга = ПамятнаяКнига()
        книга.fromPhoneBook(книгаТелефона)
        книга.matched(mapOf("+79160001122" to "u-1"))

        val итог = SyncBook(
            phones = { PhoneBookRead.Entries(книгаТелефона) },
            book = книга,
            discovery = { error("сеть отвалилась") },
        ).run()

        assertTrue(итог is SyncStep.Offline, "обрыв сети выдан за успех: $итог")
        // Отметка на месте: пустой ответ вместо ошибки снял бы её со всех разом.
        assertTrue(книга.строки.value.single { it.phone == "+79160001122" }.inTima)
        assertEquals(2, книга.строки.value.size)
    }

    @Test
    fun отказ_в_разрешении_и_отсутствие_книги_различаются() = runTest {
        val книга = ПамятнаяКнига()
        val отказ = SyncBook({ PhoneBookRead.Denied }, книга, { emptyMap() }).run()
        val нетКниги = SyncBook({ PhoneBookRead.NoBook }, книга, { emptyMap() }).run()

        assertEquals(SyncStep.NeedPermission, отказ)
        assertEquals(SyncStep.NoBook, нетКниги)
        // Ни в одном случае сверки не было: спрашивать сервер не о чем.
        assertEquals(0, книга.сверок)
    }

    @Test
    fun сверяется_вся_книга_а_не_только_прочитанное() = runTest {
        val книга = ПамятнаяКнига()
        книга.addManually("+79267778899", "Виктор", "Дом")

        var спрошено = emptyList<String>()
        SyncBook(
            phones = { PhoneBookRead.Entries(книгаТелефона) },
            book = книга,
            discovery = { phones -> спрошено = спрошено + phones; phones.associateWith { null } },
        ).run()

        // Заведённый вручную тоже мог появиться в TIMa: не спросить о нём значит
        // навсегда оставить его «только в телефоне».
        assertTrue("+79267778899" in спрошено, "ручной контакт не сверялся: $спрошено")
        assertEquals(3, спрошено.size)
    }

    @Test
    fun длинная_книга_уходит_частями() = runTest {
        val книга = ПамятнаяКнига()
        val много = (1..250).map { PhoneBookEntry("+7916000${it.toString().padStart(4, '0')}", "N$it") }

        var частей = 0
        SyncBook(
            phones = { PhoneBookRead.Entries(много) },
            book = книга,
            discovery = { phones -> частей++; phones.associateWith { null } },
        ).run(batch = 100)

        assertEquals(3, частей, "книга ушла не частями: предел сервера — 2000 за раз")
    }
}
