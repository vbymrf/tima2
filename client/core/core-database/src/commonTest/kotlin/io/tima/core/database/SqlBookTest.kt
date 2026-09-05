package io.tima.core.database

import io.tima.domain.chat.ObserveBook
import io.tima.domain.chat.PhoneBookEntry
import io.tima.domain.chat.normalizePhone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Книга контактов (ПЛАН-КОНТАКТОВ.md, Д2).
 *
 * Проверяются четыре обещания, ради которых книга и сделана своей, а не зеркалом
 * телефонной: убранный не возвращается, заведённый руками не пропадает, повторное
 * чтение не плодит строк, а правка имени переживает синхронизацию — и при этом не
 * мешает заметить, что имя в телефоне сменилось.
 */
class SqlBookTest {

    private val db = testDatabase()
    private val book = SqlBook(db, testCipher())
    private val наблюдение = ObserveBook(book)

    private suspend fun строки() = наблюдение.list().first()

    @Test
    fun чтение_книги_телефона_наполняет_список() = runTest {
        book.fromPhoneBook(
            listOf(
                PhoneBookEntry("+79160001122", "Борис Мельник"),
                PhoneBookEntry("+79035554433", "Анна Ковалёва"),
            ),
        )
        val список = строки()
        assertEquals(2, список.size)
        assertEquals("Анна Ковалёва", список.first().name)
    }

    @Test
    fun повторное_чтение_не_плодит_строк() = runTest {
        val книга = listOf(PhoneBookEntry("+79160001122", "Борис"))
        book.fromPhoneBook(книга)
        book.fromPhoneBook(книга)
        book.fromPhoneBook(книга)
        assertEquals(1, строки().size)
    }

    @Test
    fun убранный_не_возвращается_синхронизацией() = runTest {
        book.fromPhoneBook(listOf(PhoneBookEntry("+79160001122", "Борис")))
        book.hide("+79160001122")
        assertTrue(строки().isEmpty())

        // Ровно тот случай, ради которого книга своя: в телефоне он остался.
        book.fromPhoneBook(listOf(PhoneBookEntry("+79160001122", "Борис")))
        assertTrue(строки().isEmpty(), "убранный вернулся из телефонной книги")
    }

    @Test
    fun заведённый_руками_не_исчезает() = runTest {
        book.addManually("+79267778899", "Виктор, сосед", "Дом")
        // Синхронизация принесла телефонную книгу, и его там нет.
        book.fromPhoneBook(listOf(PhoneBookEntry("+79160001122", "Борис")))

        val виктор = строки().single { it.phone == "+79267778899" }
        assertEquals("Виктор, сосед", виктор.name)
        assertEquals("Дом", виктор.section)
        assertTrue(виктор.manual)
    }

    @Test
    fun своё_имя_переживает_синхронизацию() = runTest {
        book.fromPhoneBook(listOf(PhoneBookEntry("+79160001122", "Борис Мельник")))
        book.rename("+79160001122", "Боря с работы")

        // В телефоне имя сменилось — и это видно, но своё остаётся своим.
        book.fromPhoneBook(listOf(PhoneBookEntry("+79160001122", "Мельник Б. А.")))

        val строка = строки().single()
        assertEquals("Боря с работы", строка.name, "правку затёрла синхронизация")
        assertEquals("Мельник Б. А.", строка.namePhone, "смена имени в телефоне не замечена")
    }

    @Test
    fun сверка_отмечает_кто_в_приложении() = runTest {
        book.fromPhoneBook(
            listOf(
                PhoneBookEntry("+79160001122", "Борис"),
                PhoneBookEntry("+79267778899", "Виктор"),
            ),
        )
        book.matched(mapOf("+79160001122" to "u-1", "+79267778899" to null))

        val (борис, виктор) = строки().partition { it.phone == "+79160001122" }
        assertTrue(борис.single().inTima)
        assertTrue(!виктор.single().inTima)

        // Человек ушёл из TIMa: отметка снимается, а не остаётся прежней — иначе
        // экран предложит написать тому, кого уже нет.
        book.matched(mapOf("+79160001122" to null))
        assertTrue(!строки().single { it.phone == "+79160001122" }.inTima)
    }

    @Test
    fun убранный_раздел_не_уносит_людей() = runTest {
        book.addSection("Дача")
        book.addManually("+79267778899", "Виктор", "Дача")
        assertEquals(listOf("Дача"), наблюдение.sections().first())

        book.removeSection("Дача")
        assertTrue(наблюдение.sections().first().isEmpty())
        assertEquals("", строки().single().section, "человек исчез вместе с разделом")
    }

    @Test
    fun безымянные_уходят_в_конец() = runTest {
        book.fromPhoneBook(
            listOf(
                PhoneBookEntry("+79267778899", null),
                PhoneBookEntry("+79160001122", "Борис"),
            ),
        )
        val список = строки()
        assertEquals("Борис", список.first().name)
        assertNull(список.last().name)
    }

    @Test
    fun номера_приводятся_к_одному_виду() {
        val ожидаемый = "+79160001122"
        assertEquals(ожидаемый, normalizePhone("+7 916 000-11-22"))
        assertEquals(ожидаемый, normalizePhone("8 (916) 000-11-22"))
        assertEquals(ожидаемый, normalizePhone("79160001122"))
        assertEquals(ожидаемый, normalizePhone("916 000 11 22"))
        // Не номер — не догадываемся: строка из четырёх цифр может быть чем угодно.
        assertNull(normalizePhone("1234"))
        assertNull(normalizePhone("нет номера"))
        // Чужая страна остаётся чужой: код по умолчанию не приписывается тому,
        // у кого он уже есть.
        assertEquals("+441632960011", normalizePhone("+44 1632 960011"))
    }
}
