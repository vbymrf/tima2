package io.tima.shared

import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Книга по ключу для строк списков (ПЛАН-(БС) Э5.3): те же ответы, что давали проходы. */
class BookIndexTest {
    private val anya = BookEntry(id = "tima:u-1", phone = "+7001", nameOwn = "Аня", userId = "u-1")
    private val byNick = BookEntry(id = "tima:u-2", nameOwn = "Ник", userId = "u-2")
    private val bad = BookEntry(id = "tima:u-3", phone = "+7003", userId = "u-3", list = BookList.Blocked)

    private val index = BookIndex(all = listOf(anya, byNick), everyone = listOf(anya, byNick, bad))

    @Test
    fun человек_находится_по_идентификатору_а_нет_так_по_номеру() {
        assertEquals(anya, index.entry("u-1"))
        assertEquals(anya, index.entry("u-новый", phone = "+7001"))
        assertNull(index.entry("u-новый", phone = "+7999"))
    }

    @Test
    fun пустой_номер_никого_не_находит() {
        assertNull(index.entry("u-новый", phone = ""))
    }

    @Test
    fun заблокированный_не_в_книге_но_заблокирован() {
        assertFalse(index.has("u-3"))
        assertTrue(index.blocked("u-3"))
        assertFalse(index.blocked("u-1"))
    }

    @Test
    fun первая_строка_с_тем_же_ключом_главнее() {
        val second = anya.copy(id = "tel:+7001", nameOwn = "Анна")
        assertEquals("Аня", BookIndex(listOf(anya, second), emptyList()).entry("u-1")?.nameOwn)
    }
}
