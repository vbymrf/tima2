package io.tima.feature.chat

import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookList
import kotlin.test.Test
import kotlin.test.assertEquals

/** Журнал контактов — ВЗ8: два фильтра складываются, поиск по имени и номеру. */
class LedgerRowsTest {

    private val саша = BookEntry(id = "1", phone = "+79990000001", namePhone = "Саша", sectionId = "work")
    private val аня = BookEntry(id = "2", phone = "+79990000002", nameOwn = "Аня", manual = true)
    private val блок = BookEntry(id = "3", phone = "+79990000003", namePhone = "Борис", sectionId = "work", list = BookList.Blocked)
    private val все = listOf(саша, аня, блок)

    private fun ids(rows: List<BookEntry>) = rows.map { it.id }

    @Test
    fun фильтры_складываются() {
        assertEquals(listOf("3"), ids(ledgerRows(все, LedgerList.Blocked, "work", "") { false }))
        assertEquals(listOf("1"), ids(ledgerRows(все, LedgerList.Book, "work", "") { false }))
    }

    @Test
    fun общий_раздел_пустым_идентификатором() {
        assertEquals(listOf("2"), ids(ledgerRows(все, LedgerList.All, "", "") { false }))
    }

    @Test
    fun поиск_по_имени_и_номеру() {
        assertEquals(listOf("2"), ids(ledgerRows(все, LedgerList.All, null, "ан") { false }))
        assertEquals(listOf("3"), ids(ledgerRows(все, LedgerList.All, null, "0003") { false }))
    }

    @Test
    fun со_своей_мелодией() {
        assertEquals(listOf("1"), ids(ledgerRows(все, LedgerList.OwnSound, null, "") { it.id == "1" }))
    }

    @Test
    fun по_имени_по_алфавиту() {
        assertEquals(listOf("2", "3", "1"), ids(ledgerRows(все, LedgerList.All, null, "") { false }))
    }
}
