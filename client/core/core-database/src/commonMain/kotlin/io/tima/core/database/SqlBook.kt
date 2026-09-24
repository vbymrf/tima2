package io.tima.core.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.core.outbox.FieldCipher
import io.tima.domain.chat.Book
import io.tima.domain.chat.COMMON_SECTION_ID
import io.tima.domain.chat.CopySection
import io.tima.domain.chat.CopyContact
import io.tima.domain.chat.BookCopyPort
import io.tima.domain.chat.BookCopy
import io.tima.domain.chat.BookEntry
import io.tima.domain.chat.BookKey
import io.tima.domain.chat.BookList
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi
import io.tima.domain.chat.Section
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
@OptIn(ExperimentalUuidApi::class)
class SqlBook(
    private val db: TimaDatabase,
    private val cipher: FieldCipher,
    private val io: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Штамп правки: часы и устройство (миграция 10 → 11). По ним копии с разных устройств
     * сливаются построчно — побеждает поздняя. По умолчанию — нулевое устройство и нулевые
     * часы: так собирают тесты, которым слияние не нужно.
     */
    private val now: () -> Long = { 0L },
    private val device: () -> String = { "" },
) : Book, BookCopyPort {

    override fun list(): Flow<List<BookEntry>> = db.bookQueries.all(::entryOf).stream()

    override fun everyone(): Flow<List<BookEntry>> = db.bookQueries.everyone(::entryOf).stream()

    override fun sections(): Flow<List<Section>> =
        db.bookQueries.sections().asFlow().mapToList(io).map { rows ->
            rows.map { Section(id = it.id, name = it.name, icon = it.icon.toInt(), place = it.place.toInt()) }
        }

    /**
     * Строка книги из колонок запроса.
     *
     * Передаётся сгенерированным запросам вместо их собственного типа: `all` и `everyone`
     * отличаются одним условием в SQL, а строку собирают одинаково — и типы у них разные,
     * без общего предка. Так сборка одна, и новая колонка ломает компиляцию в одном месте.
     */
    @Suppress("LongParameterList")
    private fun entryOf(
        id: String,
        phone: String?,
        namePhoneEnc: ByteArray?,
        nameOwnEnc: ByteArray?,
        sectionId: String,
        userId: String?,
        manual: Long,
        list: Long,
        known: Long,
    ): BookEntry = BookEntry(
        id = id,
        phone = phone.orEmpty(),
        namePhone = namePhoneEnc?.let(::open),
        nameOwn = nameOwnEnc?.let(::open),
        sectionId = sectionId,
        userId = userId,
        manual = manual != 0L,
        list = BookList.of(list.toInt()),
        known = known != 0L,
    )

    /** Порядок задаётся здесь — см. заметку у класса. */
    private fun Query<BookEntry>.stream(): Flow<List<BookEntry>> =
        asFlow().mapToList(io).map { rows ->
            rows.sortedWith(
                // Безымянные — в конец: у них нечего читать глазами, и держать их
                // среди названных значит мешать поиску взглядом.
                compareBy({ it.name == null }, { it.name ?: it.phone }),
            )
        }

    override suspend fun fromPhoneBook(entries: List<PhoneBookEntry>) = withContext(io) {
        // Одной транзакцией: чтение книги телефона — сотни строк, и по строке на запрос
        // означало бы сотни коммитов и видимый список, меняющийся на глазах.
        db.transaction {
            entries.forEach { entry ->
                val id = BookKey.ofPhone(entry.phone)
                db.bookQueries.fromPhoneBookInsert(id, entry.phone)
                db.bookQueries.fromPhoneBookName(entry.name?.let(::seal), id)
            }
        }
    }

    override suspend fun addManually(phone: String, name: String?, sectionId: String) =
        withContext(io) {
            val id = BookKey.ofPhone(phone)
            db.transaction {
                db.bookQueries.addManuallyInsert(id, phone, null)
                db.bookQueries.addManuallyFields(name?.let(::seal), sectionId, now(), device(), id)
            }
        }

    override suspend fun addByUser(userId: String, name: String?, sectionId: String) =
        withContext(io) {
            db.transaction {
                // Строка про этого человека уже может быть — заведённая по номеру из
                // телефонной книги. Второй её не заводим: это был бы один человек двумя
                // строками, и слить их потом было бы нечем.
                val existing = db.bookQueries.byUserId(userId).executeAsOneOrNull()
                val id = existing ?: BookKey.ofUser(userId)
                db.bookQueries.addManuallyInsert(id, null, userId)
                db.bookQueries.addManuallyFields(name?.let(::seal), sectionId, now(), device(), id)
            }
        }

    override suspend fun rename(id: String, name: String?): Unit = withContext(io) {
        db.bookQueries.setOwnName(name?.let(::seal), now(), device(), id)
    }

    override suspend fun moveTo(id: String, sectionId: String): Unit = withContext(io) {
        db.bookQueries.setSection(sectionId, now(), device(), id)
    }

    override suspend fun setList(id: String, list: BookList, known: Boolean): Unit = withContext(io) {
        db.bookQueries.setList(
            list = list.wire.toLong(),
            known = if (known) 1L else 0L,
            updatedAt = now(),
            device = device(),
            id = id,
        )
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

    override suspend fun setCommon(name: String, icon: Int): Unit = withContext(io) {
        val clean = name.trim().ifEmpty { return@withContext }
        db.transaction {
            // Строки может не быть: «Общий» существует и без неё. Заводим с местом в конце —
            // он всегда последний, — и правим тем же запросом, что обычные разделы.
            if (db.bookQueries.sections().executeAsList().none { it.id == COMMON_SECTION_ID }) {
                db.bookQueries.addSection(COMMON_SECTION_ID, clean, icon.toLong(), COMMON_PLACE, now(), device())
            }
            db.bookQueries.renameSection(clean, icon.toLong(), now(), device(), COMMON_SECTION_ID)
        }
    }

    override suspend fun addSection(name: String, icon: Int): String = withContext(io) {
        val clean = name.trim()
        db.transactionWithResult {
            // Имён-двойников не заводим: два раздела «Работа» различимы только по порядку,
            // а это не различие. Существующий — возвращается, как если бы завели его.
            db.bookQueries.sectionByName(clean).executeAsOneOrNull()?.let { return@transactionWithResult it }
            // Порядок — по времени появления: раздел, заведённый позже, встаёт ниже.
            // Число берётся из размера списка, а не из времени: время у двух разделов,
            // заведённых подряд, совпадает.
            val place = db.bookQueries.sections().executeAsList().size.toLong()
            // Идентификатор случайный и заводится здесь один раз — дальше он живёт в копии
            // на всех устройствах человека и не меняется никогда (миграция 8 → 9).
            val id = Uuid.random().toString()
            db.bookQueries.addSection(id, clean, icon.toLong(), place, now(), device())
            id
        }
    }

    override suspend fun renameSection(id: String, name: String, icon: Int): Unit = withContext(io) {
        db.bookQueries.renameSection(name.trim(), icon.toLong(), now(), device(), id)
    }

    override suspend fun placeSection(id: String, place: Int): Unit = withContext(io) {
        db.bookQueries.placeSection(place.toLong(), now(), device(), id)
    }

    override suspend fun removeSection(id: String) = withContext(io) {
        db.transaction {
            db.bookQueries.emptySection(id)
            db.bookQueries.removeSection(now(), device(), id)
        }
    }

    // ── Копия для других устройств (Р2а) ──────────────────────────────────────

    override suspend fun snapshot(): BookCopy = withContext(io) {
        BookCopy(
            revision = 0,
            device = device(),
            contacts = db.bookQueries.copyContacts().executeAsList().map {
                CopyContact(
                    id = it.id,
                    phone = it.phone.orEmpty(),
                    userId = it.user_id,
                    nameOwn = it.name_own_enc?.let(::open),
                    sectionId = it.section_id,
                    manual = it.manual != 0L,
                    list = it.list.toInt(),
                    known = it.known != 0L,
                    updatedAt = it.updated_at,
                    device = it.device,
                )
            },
            sections = db.bookQueries.copySections().executeAsList().map {
                CopySection(
                    id = it.id,
                    name = it.name,
                    icon = it.icon.toInt(),
                    place = it.place.toInt(),
                    deleted = it.deleted != 0L,
                    updatedAt = it.updated_at,
                    device = it.device,
                )
            },
        )
    }

    /**
     * Применяются только записи МОЛОЖЕ наших. Сравнение — здесь, в той же транзакции, что и
     * запись: между снимком и применением человек мог что-то поправить, и его правка моложе
     * всего пришедшего — затереть её значило бы потерять то, что он только что сделал.
     */
    override suspend fun apply(theirs: BookCopy): Unit = withContext(io) {
        db.transaction {
            for (c in theirs.contacts) {
                val ours = db.bookQueries.contactStamp(c.id).executeAsOneOrNull()
                if (ours != null && ours >= c.updatedAt) continue
                db.bookQueries.copyPutContact(
                    id = c.id,
                    phone = c.phone.ifBlank { null },
                    nameOwnEnc = c.nameOwn?.let(::seal),
                    sectionId = c.sectionId,
                    userId = c.userId,
                    manual = if (c.manual) 1L else 0L,
                    list = c.list.toLong(),
                    known = if (c.known) 1L else 0L,
                    updatedAt = c.updatedAt,
                    device = c.device,
                )
            }
            for (s in theirs.sections) {
                val ours = db.bookQueries.sectionStamp(s.id).executeAsOneOrNull()
                if (ours != null && ours >= s.updatedAt) continue
                db.bookQueries.copyPutSection(
                    s.id, s.name, s.icon.toLong(), s.place.toLong(),
                    if (s.deleted) 1L else 0L, s.updatedAt, s.device,
                )
            }
        }
    }

    private fun seal(text: String): ByteArray = cipher.seal(text.encodeToByteArray())

    private fun open(blob: ByteArray): String? = cipher.open(blob)?.decodeToString()
}

/** Место «Общего» в порядке: заведомо больше любого обычного — он всегда последний. */
private const val COMMON_PLACE = 1_000L
