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
    private val шифр = тестовыйШифр()
    private val книга = SqlChatBook(db, шифр)
    private val контакты = SqlContacts(db, шифр)

    @Test
    fun группы_в_контакты_не_попадают() = runTest {
        // У группы нет собеседника: в контактах ей делать нечего, а peer_id у неё NULL.
        книга.remember("g-1", ChatKind.Group, title = "Поход", peerId = null)
        книга.remember("c-1", ChatKind.Personal, title = "Аня", peerId = "u-2")

        val список = контакты.list().first()

        assertEquals(1, список.size)
        assertEquals("u-2", список.single().userId)
    }

    @Test
    fun имя_расшифровывается() = runTest {
        книга.remember("c-1", ChatKind.Personal, title = "Аня", peerId = "u-2")
        assertEquals("Аня", контакты.list().first().single().name)
    }

    @Test
    fun человек_с_нечитаемым_именем_остаётся_в_списке() = runTest {
        // Не открылось — строка остаётся без имени, но остаётся: переписка существует, и
        // потерять собеседника из-за одной испорченной записи нельзя.
        книга.remember("c-1", ChatKind.Personal, title = null, peerId = "u-2")

        val контакт = контакты.list().first().single()

        assertEquals("u-2", контакт.userId)
        assertNull(контакт.name)
    }

    @Test
    fun порядок_по_имени_а_безымянные_в_конце() = runTest {
        // Сортировать в SQL нечем: имя зашифровано, и ORDER BY по шифртексту дал бы
        // случайный порядок, меняющийся при каждой перезаписи.
        книга.remember("c-1", ChatKind.Personal, title = "Яна", peerId = "u-9")
        книга.remember("c-2", ChatKind.Personal, title = null, peerId = "u-0")
        книга.remember("c-3", ChatKind.Personal, title = "Аня", peerId = "u-2")

        val имена = контакты.list().first().map { it.name }

        assertEquals(listOf("Аня", "Яна", null), имена)
    }

    @Test
    fun пустая_база_даёт_пустой_список() = runTest {
        assertTrue(контакты.list().first().isEmpty())
    }
}
