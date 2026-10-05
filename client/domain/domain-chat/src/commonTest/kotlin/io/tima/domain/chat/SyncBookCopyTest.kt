package io.tima.domain.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Копия книги между устройствами (ПЛАН-(РЗ)-РАЗДЕЛОВ Р2а) — без сети, ключей и телефона.
 *
 * Проверяется ровно то, ради чего сделано построчное слияние: два устройства правят разное,
 * и ни одна правка не теряется; одну и ту же запись — побеждает поздняя; убранное с одного
 * устройства не возвращается с другого.
 */
class SyncBookCopyTest {

    private fun contact(phone: String, name: String?, section: String = "", at: Long, device: String, hidden: Boolean = false) =
        CopyContact(
            id = BookKey.ofPhone(phone), phone = phone,
            nameOwn = name, sectionId = section, manual = true,
            list = if (hidden) BookList.Removed.wire else BookList.Usual.wire,
            updatedAt = at, device = device,
        )

    private fun section(id: String, name: String, at: Long, device: String, deleted: Boolean = false) =
        CopySection(id, name, icon = 0, place = 0, deleted = deleted, updatedAt = at, device = device)

    @Test
    fun правки_разных_записей_с_двух_устройств_складываются() {
        val phone = BookCopy(1, "T1", listOf(contact("+1", "Витя", at = 10, device = "T1")), listOf(section("s", "Работа", at = 10, device = "T1")))
        val pc = BookCopy(1, "P2", listOf(contact("+1", "Витя", at = 10, device = "T1"), contact("+2", "Анна", at = 20, device = "P2")), listOf(section("s", "Офис", at = 30, device = "P2")))
        val merged = BookCopy.merge(phone, pc)
        assertEquals(setOf("+1", "+2"), merged.contacts.map { it.phone }.toSet(), "контакт с ПК обязан остаться")
        assertEquals("Офис", merged.sections.single().name, "переименование с ПК обязано победить: оно моложе")
    }

    @Test
    fun одну_запись_с_двух_сторон_берёт_поздняя() {
        val a = BookCopy(1, "T1", listOf(contact("+1", "Витя-сосед", at = 50, device = "T1")), emptyList())
        val b = BookCopy(1, "P2", listOf(contact("+1", "Виктор", at = 40, device = "P2")), emptyList())
        assertEquals("Витя-сосед", BookCopy.merge(a, b).contacts.single().nameOwn)
        assertEquals("Витя-сосед", BookCopy.merge(b, a).contacts.single().nameOwn, "порядок аргументов не должен менять исход")
    }

    @Test
    fun надгробие_не_даёт_убранному_вернуться() {
        val phone = BookCopy(1, "T1", listOf(contact("+1", "Витя", at = 90, device = "T1", hidden = true)), listOf(section("s", "Дача", at = 90, device = "T1", deleted = true)))
        val pc = BookCopy(1, "P2", listOf(contact("+1", "Витя", at = 10, device = "P2")), listOf(section("s", "Дача", at = 10, device = "P2")))
        val merged = BookCopy.merge(pc, phone)
        assertEquals(BookList.Removed.wire, merged.contacts.single().list, "убранный на телефоне контакт не должен вернуться с ПК")
        assertTrue(merged.sections.single().deleted, "убранный раздел не должен вернуться")
    }

    // ── Два хода ────────────────────────────────────────────────────────────

    private class MemoryCopy(var mine: BookCopy) : BookCopyPort {
        val applied = mutableListOf<BookCopy>()
        override suspend fun snapshot() = mine
        override suspend fun apply(theirs: BookCopy) {
            applied += theirs
            mine = BookCopy.merge(mine, theirs)
        }
    }

    /** Кодек без шифра: копия едет как есть. Шифр проверяется в core-encryption. */
    private object Plain : BookCopyCodec {
        val table = mutableMapOf<Int, BookCopy>()
        override fun seal(key: ByteArray, copy: BookCopy): ByteArray {
            val id = table.size + 1
            table[id] = copy
            return byteArrayOf(id.toByte())
        }
        override fun open(key: ByteArray, sealed: ByteArray) = table[sealed[0].toInt()]
    }

    /**
     * Ячейка сервера. Устройство сервер берёт из токена, а не из тела — здесь его роль
     * играет [caller]: чей токен сейчас «в запросе».
     */
    private class Cell : AccountStorePort {
        var revision = 0L
        var blob: ByteArray? = null
        var device = ""
        var caller = ""
        override suspend fun fetch(): AccountStoreStep =
            blob?.let { AccountStoreStep.Blob(revision, device, it) } ?: AccountStoreStep.Empty
        override suspend fun put(revision: Long, blob: ByteArray): AccountStoreStep {
            if (revision != this.revision + 1) {
                return AccountStoreStep.Conflict(AccountStoreStep.Blob(this.revision, device, this.blob!!))
            }
            this.revision = revision
            this.blob = blob
            this.device = caller
            return AccountStoreStep.Stored
        }
    }

    private class Memory : RevisionMemory {
        var value = 0L
        override fun last() = value
        override fun remember(revision: Long) { value = revision }
    }

    /** Кодек, который открывает только тем ключом, которым запечатано. */
    private object Keyed : BookCopyCodec {
        val table = mutableMapOf<Int, Pair<Byte, BookCopy>>()
        override fun seal(key: ByteArray, copy: BookCopy): ByteArray {
            val id = table.size + 1
            table[id] = key[0] to copy
            return byteArrayOf(id.toByte())
        }
        override fun open(key: ByteArray, sealed: ByteArray) = table[sealed[0].toInt()]?.takeIf { it.first == key[0] }?.second
    }

    /**
     * realme 2026-10-05: книга запечатана версией 1 ключа служебной группы, потом ключ сменился
     * (версия 2) — и копия перестала открываться, а записать новую поверх тоже было нельзя.
     */
    @Test
    fun копия_под_прежней_версией_ключа_открывается() = runTest {
        val cell = Cell()
        val phone = MemoryCopy(BookCopy(0, "T1", listOf(contact("+1", "Витя", at = 10, device = "T1")), emptyList()))
        cell.caller = "T1"
        assertIs<CopyStep.Pushed>(SyncBookCopy(phone, cell, Keyed, { byteArrayOf(1) }, Memory(), { "T1" }).push())

        val withoutOld = SyncBookCopy(MemoryCopy(BookCopy.EMPTY), cell, Keyed, { byteArrayOf(2) }, Memory(), { "T1" })
        assertIs<CopyStep.Refused>(withoutOld.pull(), "одной последней версией прежнюю копию не открыть")

        val after = MemoryCopy(BookCopy.EMPTY.copy(device = "T1"))
        val sync = SyncBookCopy(after, cell, Keyed, { byteArrayOf(2) }, Memory(), { "T1" }, olderKeys = { listOf(byteArrayOf(1)) })
        assertIs<CopyStep.Pulled>(sync.pull())
        assertEquals("Витя", after.mine.contacts.single().nameOwn)
        after.mine = after.mine.copy(contacts = after.mine.contacts + contact("+2", "Анна", at = 20, device = "T1"))
        assertIs<CopyStep.Pushed>(sync.push(), "после открытия копия снова пишется — уже последней версией")
        assertEquals(2.toByte(), Keyed.table.getValue(cell.blob!![0].toInt()).first)
    }

    /**
     * Р52: ключа, которым закрыта серверная копия, нет ни у одного своего устройства (Redmi после
     * стирания). Новая версия ключа — и своя книга поверх, без слияния: прежнюю открыть нечем.
     */
    @Test
    fun книга_без_прежнего_ключа_начинается_заново() = runTest {
        val cell = Cell()
        cell.caller = "T1"
        assertIs<CopyStep.Pushed>(SyncBookCopy(MemoryCopy(BookCopy(0, "T1", listOf(contact("+1", "Витя", at = 10, device = "T1")), emptyList())), cell, Keyed, { byteArrayOf(7) }, Memory(), { "T1" }).push())
        val before = cell.revision

        val wiped = MemoryCopy(BookCopy(0, "R3", listOf(contact("+3", "Оля", at = 30, device = "R3")), emptyList()))
        val sync = SyncBookCopy(wiped, cell, Keyed, { byteArrayOf(8) }, Memory(), { "R3" })
        cell.caller = "R3"
        assertIs<CopyStep.Refused>(sync.push(), "слить с копией, которую нечем открыть, нельзя — запись застревает")
        val restarted = sync.restart()
        assertIs<CopyStep.Pushed>(restarted)
        assertEquals(before + 1, restarted.revision)
        val (key, copy) = Keyed.table.getValue(cell.blob!![0].toInt())
        assertEquals(8.toByte(), key, "новая копия — под новой версией ключа")
        assertEquals(listOf("+3"), copy.contacts.map { it.phone })
        assertIs<CopyStep.Unchanged>(sync.push(), "после перезапуска та же книга второй раз не уходит")
    }

    @Test
    fun отдать_потом_забрать_на_втором_устройстве() = runTest {
        val cell = Cell()
        val phone = MemoryCopy(BookCopy(0, "T1", listOf(contact("+1", "Витя", at = 10, device = "T1")), emptyList()))
        val syncPhone = SyncBookCopy(phone, cell, Plain, { byteArrayOf(1) }, Memory(), { "T1" })
        cell.caller = "T1"
        assertIs<CopyStep.Pushed>(syncPhone.push())

        val pc = MemoryCopy(BookCopy.EMPTY.copy(device = "P2"))
        val syncPc = SyncBookCopy(pc, cell, Plain, { byteArrayOf(1) }, Memory(), { "P2" })
        val pulled = syncPc.pull()
        assertIs<CopyStep.Pulled>(pulled)
        assertEquals("T1", pulled.from)
        assertEquals("Витя", pc.mine.contacts.single().nameOwn)
    }

    @Test
    fun гонка_двух_устройств_кончается_слиянием_а_не_потерей() = runTest {
        val cell = Cell()
        val phone = MemoryCopy(BookCopy(0, "T1", listOf(contact("+1", "Витя", at = 10, device = "T1")), emptyList()))
        val pc = MemoryCopy(BookCopy(0, "P2", listOf(contact("+2", "Анна", at = 20, device = "P2")), emptyList()))
        val memPhone = Memory()
        val memPc = Memory()
        val syncPhone = SyncBookCopy(phone, cell, Plain, { byteArrayOf(1) }, memPhone, { "T1" })
        val syncPc = SyncBookCopy(pc, cell, Plain, { byteArrayOf(1) }, memPc, { "P2" })

        cell.caller = "T1"
        assertIs<CopyStep.Pushed>(syncPhone.push())      // ревизия 1
        cell.caller = "P2"
        val second = syncPc.push()                        // шлёт 1 → 409 → сливает → шлёт 2
        assertIs<CopyStep.Pushed>(second)
        assertEquals(2L, second.revision)
        assertEquals(setOf("+1", "+2"), pc.mine.contacts.map { it.phone }.toSet(), "после гонки у ПК обязаны быть оба контакта")
        assertEquals(2L, memPc.value)
    }

    @Test
    fun перезапуск_без_правок_не_отдаёт_ту_же_книгу_снова() = runTest {
        // Redmi 2026-09-30: 127 → 128 → 129 за четыре минуты без единой правки — отпечаток
        // отданного жил в памяти и терялся с перезапуском (ЖУ9).
        val cell = Cell()
        val phone = MemoryCopy(BookCopy(0, "T1", listOf(contact("+1", "Витя", at = 10, device = "T1")), emptyList()))
        var print: Int? = null
        fun запуск() = SyncBookCopy(phone, cell, Plain, { byteArrayOf(1) }, Memory().apply { value = cell.revision }, { "T1" },
            lastPrint = { print }, rememberPrint = { print = it })
        cell.caller = "T1"
        assertIs<CopyStep.Pushed>(запуск().push())

        assertEquals(CopyStep.Unchanged, запуск().push(), "второй запуск без правок")
        assertEquals(1L, cell.revision)
    }

    @Test
    fun без_ключа_ходов_нет() = runTest {
        val sync = SyncBookCopy(MemoryCopy(BookCopy.EMPTY), Cell(), Plain, { null }, Memory(), { "T1" })
        assertEquals(CopyStep.NoKey, sync.pull())
        assertEquals(CopyStep.NoKey, sync.push())
    }
}
