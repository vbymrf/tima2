package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.core.outbox.FieldCipher
import io.tima.domain.chat.Book
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.PhoneBookEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Книга контактов в местной базе — переходник к порту `domain-chat`.
 *
 * **Имена расшифровываются здесь**, как и названия переписок: имя человека — содержимое,
 * а не метаданные. Не открылось — строка остаётся без имени, но остаётся: контакт не
 * должен исчезать из книги из-за одной испорченной записи.
 *
 * **Порядок задаётся в памяти, а не запросом.** Сортировать в SQL нечем: имена
 * зашифрованы, и `ORDER BY name_own_enc` дал бы порядок по шифртексту — случайный и
 * меняющийся при каждой перезаписи.
 */
class SqlBook(
    private val db: TimaDatabase,
    private val cipher: FieldCipher,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : Book {

    override fun list(): Flow<List<BookEntry>> =
        db.bookQueries.all()
            .asFlow()
            .mapToList(io)
            .map { rows ->
                rows.map { row ->
                    BookEntry(
                        phone = row.phone,
                        namePhone = row.name_phone_enc?.let(::open),
                        nameOwn = row.name_own_enc?.let(::open),
                        section = row.section,
                        userId = row.user_id,
                        manual = row.manual != 0L,
                    )
                }.sortedWith(
                    // Безымянные — в конец: у них нечего читать глазами, и держать их
                    // среди названных значит мешать поиску взглядом.
                    compareBy({ it.name == null }, { it.name ?: it.phone }),
                )
            }

    override fun sections(): Flow<List<String>> =
        db.bookQueries.sections().asFlow().mapToList(io).map { rows -> rows.map { it.name } }

    override suspend fun fromPhoneBook(entries: List<PhoneBookEntry>) = withContext(io) {
        // Одной транзакцией: чтение книги телефона — сотни строк, и по строке на запрос
        // означало бы сотни коммитов и видимый список, меняющийся на глазах.
        db.transaction {
            entries.forEach { entry ->
                db.bookQueries.fromPhoneBookInsert(entry.phone)
                db.bookQueries.fromPhoneBookName(entry.name?.let(::seal), entry.phone)
            }
        }
    }

    override suspend fun addManually(phone: String, name: String?, section: String) =
        withContext(io) {
            db.transaction {
                db.bookQueries.addManuallyInsert(phone)
                db.bookQueries.addManuallyFields(name?.let(::seal), section, phone)
            }
        }

    override suspend fun rename(phone: String, name: String?): Unit = withContext(io) {
        db.bookQueries.setOwnName(name?.let(::seal), phone)
    }

    override suspend fun moveTo(phone: String, section: String): Unit = withContext(io) {
        db.bookQueries.setSection(section, phone)
    }

    override suspend fun hide(phone: String): Unit = withContext(io) {
        db.bookQueries.hide(phone)
    }

    override suspend fun matched(found: Map<String, String?>) = withContext(io) {
        db.transaction {
            found.forEach { (phone, userId) ->
                // Пропажа человека из TIMa — не то же, что «его там не было»: строка
                // очищается, а не остаётся с прежним идентификатором. Иначе экран
                // предложил бы написать тому, кого уже нет.
                if (userId.isNullOrBlank()) {
                    db.bookQueries.clearUserId(phone)
                } else {
                    db.bookQueries.setUserId(userId, phone)
                }
            }
        }
    }

    override suspend fun addSection(name: String): Unit = withContext(io) {
        // Порядок — по времени появления: раздел, заведённый позже, встаёт ниже.
        // Число берётся из размера списка, а не из времени: время у двух разделов,
        // заведённых подряд, совпадает.
        val place = db.bookQueries.sections().executeAsList().size.toLong()
        db.bookQueries.addSection(name, place)
    }

    override suspend fun removeSection(name: String) = withContext(io) {
        db.transaction {
            db.bookQueries.emptySection(name)
            db.bookQueries.removeSection(name)
        }
    }

    private fun seal(text: String): ByteArray = cipher.seal(text.encodeToByteArray())

    private fun open(blob: ByteArray): String? = cipher.open(blob)?.decodeToString()
}
