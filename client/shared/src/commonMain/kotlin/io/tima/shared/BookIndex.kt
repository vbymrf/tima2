package io.tima.shared

import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookList

/**
 * Книга по ключу — для строк «Чатов» и «Звонков» (ПЛАН-(БС) Э5.3).
 *
 * Каждая строка спрашивает про своего человека: как его зовут в книге, незнакомый ли он,
 * не заблокирован ли. Прежде каждый такой вопрос проходил книгу целиком, а строка задаёт
 * их по три-четыре — и всё это при каждой перерисовке и каждой строке, выезжающей при
 * прокрутке. Индекс строится раз на смену книги, и вопрос стоит одного поиска по ключу.
 *
 * Правила те же, что были у проходов: «в книге» — среди показываемых (`all`),
 * «заблокирован» — среди всех (`everyone`); первая строка с тем же ключом главнее.
 */
internal class BookIndex(all: List<BookEntry>, everyone: List<BookEntry>) {
    private val byUser = HashMap<String, BookEntry>()
    private val byPhone = HashMap<String, BookEntry>()
    private val blocked = HashSet<String>()

    init {
        for (entry in all) {
            entry.userId?.let { if (it !in byUser) byUser[it] = entry }
            // Пустой номер — у контакта по нику; по нему никого не ищем.
            entry.phone.takeIf { it.isNotEmpty() }?.let { if (it !in byPhone) byPhone[it] = entry }
        }
        for (entry in everyone) {
            if (entry.list == BookList.Blocked) entry.userId?.let(blocked::add)
        }
    }

    /** Строка книги человека: по идентификатору, а не нашлось — по номеру его карточки. */
    fun entry(userId: String, phone: String? = null): BookEntry? =
        byUser[userId] ?: phone?.takeIf { it.isNotEmpty() }?.let(byPhone::get)

    /** Есть ли человек в показываемой книге. */
    fun has(userId: String): Boolean = userId in byUser

    fun blocked(userId: String): Boolean = userId in blocked
}
