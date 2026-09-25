package io.tima.feature.chat

import io.tima.domain.chat.BookEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Карточка во вкладке «Контакты» — заказчик 2026-09-25: одинаковое имя складывается в одну
 * строку, чтобы человек выбрал нужного «Сашу».
 */
class PackCardsTest {

    private fun entry(id: String, book: String? = null, own: String? = null) =
        BookEntry(id = id, phone = id, namePhone = book, nameOwn = own)

    @Test
    fun два_саши_из_книги_складываются_в_карточку() {
        val rows = packCards(listOf(entry("1", "Саша"), entry("2", "Аня"), entry("3", "Саша")))
        assertEquals(2, rows.size)
        val card = assertIs<BookRow.Card>(rows[0])
        assertEquals("Саша", card.name)
        assertEquals(listOf("1", "3"), card.members.map { it.id })
        assertIs<BookRow.One>(rows[1])
    }

    @Test
    fun наше_имя_и_имя_из_книги_сравниваются_как_показываемое() {
        // Наше перебивает книжное, как и в строке списка: складывается то, что человек видит.
        val rows = packCards(listOf(entry("1", book = "Александр", own = "Саша"), entry("2", book = "Саша")))
        assertEquals(1, rows.size)
        assertEquals(2, assertIs<BookRow.Card>(rows[0]).members.size)
    }

    @Test
    fun регистр_различает_а_пробелы_по_краям_нет() {
        val rows = packCards(listOf(entry("1", "Саша"), entry("2", "саша"), entry("3", " Саша ")))
        assertEquals(2, rows.size)
        assertEquals(listOf("1", "3"), assertIs<BookRow.Card>(rows[0]).members.map { it.id })
        assertEquals("2", assertIs<BookRow.One>(rows[1]).entry.id)
    }

    @Test
    fun без_имени_не_складывается() {
        // Строку без имени называет номер — одинаковым он не бывает, и складывать нечего.
        val rows = packCards(listOf(entry("1"), entry("2")))
        assertEquals(2, rows.size)
        rows.forEach { assertIs<BookRow.One>(it) }
    }

    @Test
    fun поменяли_имя_одному_карточка_распадается() {
        val rows = packCards(listOf(entry("1", book = "Саша"), entry("2", book = "Саша", own = "Саша работа")))
        rows.forEach { assertIs<BookRow.One>(it) }
    }
}
