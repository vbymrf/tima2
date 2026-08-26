package io.tima.core.database

import io.tima.domain.chat.ChatKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Контакты на настоящем SQL.
 *
 * Главное здесь — что попадает в список и в каком порядке. Контакт это человек, с
 * которым уже есть личная переписка; группа контактом не является, а нечитаемое имя не
 * повод потерять человека.
 */
class SqlContactsTest {

    private val db = testDatabase()
    private val cipher = testCipher()
    private val book = SqlChatBook(db, cipher)
    private val contacts = SqlContacts(db, cipher)

    @Test
    fun группы_в_контакты_не_попадают() = runTest {
        // У группы нет собеседника: в контактах ей делать нечего, а peer_id у неё NULL.
        book.remember("g-1", ChatKind.Group, title = "Поход", peerId = null)
        book.remember("c-1", ChatKind.Personal, title = "Аня", peerId = "u-2")

        val list = contacts.list().first()

        assertEquals(1, list.size)
        assertEquals("u-2", list.single().userId)
    }

    @Test
    fun имя_расшифровывается() = runTest {
        book.remember("c-1", ChatKind.Personal, title = "Аня", peerId = "u-2")
        assertEquals("Аня", contacts.list().first().single().name)
    }

    @Test
    fun человек_с_нечитаемым_именем_остаётся_в_списке() = runTest {
        // Не открылось — строка остаётся без имени, но остаётся: переписка существует, и
        // потерять собеседника из-за одной испорченной записи нельзя.
        book.remember("c-1", ChatKind.Personal, title = null, peerId = "u-2")

        val contact = contacts.list().first().single()

        assertEquals("u-2", contact.userId)
        assertNull(contact.name)
    }

    @Test
    fun порядок_по_имени_а_безымянные_в_конце() = runTest {
        // Сортировать в SQL нечем: имя зашифровано, и ORDER BY по шифртексту дал бы
        // случайный порядок, меняющийся при каждой перезаписи.
        book.remember("c-1", ChatKind.Personal, title = "Яна", peerId = "u-9")
        book.remember("c-2", ChatKind.Personal, title = null, peerId = "u-0")
        book.remember("c-3", ChatKind.Personal, title = "Аня", peerId = "u-2")

        val names = contacts.list().first().map { it.name }

        assertEquals(listOf("Аня", "Яна", null), names)
    }

    @Test
    fun пустая_база_даёт_пустой_список() = runTest {
        assertTrue(contacts.list().first().isEmpty())
    }
}
