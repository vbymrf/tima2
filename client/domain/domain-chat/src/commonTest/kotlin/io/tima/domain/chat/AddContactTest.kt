package io.tima.domain.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Добавление и удаление контакта (ПЛАН-КОНТАКТОВ.md, Д6 и Д7).
 *
 * Главное здесь — что «есть в контактах — друг» читается в обе стороны и что контакт
 * сохраняется даже тогда, когда сервер промолчал: человек записал номер себе в книгу, и
 * терять его из-за молчания сервера не за что.
 */
class AddContactTest {

    private class ПамятнаяКнига : Book {
        val строки = MutableStateFlow<List<BookEntry>>(emptyList())
        override fun list(): Flow<List<BookEntry>> = строки
        override fun sections(): Flow<List<String>> = MutableStateFlow(emptyList())
        override suspend fun fromPhoneBook(entries: List<PhoneBookEntry>) = Unit
        override suspend fun addManually(phone: String, name: String?, section: String) {
            строки.value = строки.value.filterNot { it.phone == phone } +
                BookEntry(phone, nameOwn = name, section = section, manual = true)
        }
        override suspend fun rename(phone: String, name: String?) = Unit
        override suspend fun moveTo(phone: String, section: String) = Unit
        override suspend fun hide(phone: String) {
            строки.value = строки.value.filterNot { it.phone == phone }
        }
        override suspend fun matched(found: Map<String, String?>) {
            строки.value = строки.value.map { row ->
                if (found.containsKey(row.phone)) row.copy(userId = found[row.phone]) else row
            }
        }
        override suspend fun addSection(name: String) = Unit
        override suspend fun removeSection(name: String) = Unit
    }

    private class ПамятныеДрузья : Friends {
        val добавлены = mutableSetOf<String>()
        override suspend fun set(userId: String, friend: Boolean): Boolean {
            if (friend) добавлены += userId else добавлены -= userId
            return true
        }
    }

    @Test
    fun найденный_становится_другом_одним_нажатием() = runTest {
        val книга = ПамятнаяКнига()
        val друзья = ПамятныеДрузья()
        val шаг = AddContact(книга, друзья, { phones -> phones.associateWith { "u-1" } })
            .add("8 916 000-11-22", "Борис", "Работа")

        assertEquals(AddStep.InTima("+79160001122", "u-1", subscribed = true), шаг)
        // Отдельного «подписаться» нет: спрашивать дважды об одном значит спрашивать зря.
        assertTrue("u-1" in друзья.добавлены)
        assertEquals("Работа", книга.строки.value.single().section)
    }

    @Test
    fun ненайденный_всё_равно_сохраняется() = runTest {
        val книга = ПамятнаяКнига()
        val друзья = ПамятныеДрузья()
        val шаг = AddContact(книга, друзья, { phones -> phones.associateWith { null } })
            .add("+7 926 777-88-99", "Виктор", "")

        assertEquals(AddStep.OnlyPhone("+79267778899"), шаг)
        assertEquals(1, книга.строки.value.size, "ненайденный номер не сохранился")
        assertTrue(друзья.добавлены.isEmpty(), "в друзья попал тот, кого нет в TIMa")
    }

    @Test
    fun без_сети_контакт_сохраняется() = runTest {
        val книга = ПамятнаяКнига()
        val шаг = AddContact(книга, ПамятныеДрузья(), { error("сети нет") })
            .add("+79160001122", null, "")

        assertEquals(AddStep.OnlyPhone("+79160001122"), шаг)
        assertEquals(1, книга.строки.value.size)
    }

    @Test
    fun из_мусора_контакта_не_выходит() = runTest {
        val книга = ПамятнаяКнига()
        val шаг = AddContact(книга, ПамятныеДрузья(), { emptyMap() }).add("не номер", "Никто", "")

        assertEquals(AddStep.BadPhone, шаг)
        // Единственный случай, когда не сохраняется ничего: сохранять нечего.
        assertTrue(книга.строки.value.isEmpty())
    }

    @Test
    fun удаление_контакта_снимает_подписку() = runTest {
        val книга = ПамятнаяКнига()
        val друзья = ПамятныеДрузья()
        AddContact(книга, друзья, { phones -> phones.associateWith { "u-1" } })
            .add("+79160001122", "Борис", "")

        RemoveContact(книга, друзья).remove(книга.строки.value.single())

        assertTrue(книга.строки.value.isEmpty())
        // «Есть в контактах — друг» читается в обе стороны: иначе лента копит тех,
        // кого человек уже убрал.
        assertTrue(друзья.добавлены.isEmpty(), "подписка осталась после удаления контакта")
    }
}
