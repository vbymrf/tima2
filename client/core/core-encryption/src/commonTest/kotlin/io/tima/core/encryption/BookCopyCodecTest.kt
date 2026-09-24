package io.tima.core.encryption

import io.tima.domain.chat.BookCopy
import io.tima.domain.chat.CopyContact
import io.tima.domain.chat.CopySection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Копия книги под ключом: закрылась — открылась той же; чужим ключом не открывается. */
class BookCopyCodecTest {

    private val copy = BookCopy(
        revision = 7,
        device = "T1",
        contacts = listOf(
            CopyContact(
                id = "tel:+79160001122", phone = "+79160001122",
                nameOwn = "Витя, сосед", sectionId = "s-1", manual = true,
                updatedAt = 1000, device = "T1",
            ),
            // Человек без номера — заведённый по нику. Едет он тем же блобом, и ключ у
            // него свой: до Л0 такой строки в копии не могло быть вовсе.
            CopyContact(
                id = "tima:u-9", userId = "u-9",
                nameOwn = "Аня", sectionId = "s-1", manual = true,
                list = 2, known = true, updatedAt = 1100, device = "T1",
            ),
        ),
        sections = listOf(CopySection("s-1", "Работа", icon = 2, place = 0, deleted = false, updatedAt = 900, device = "T1")),
    )

    @Test
    fun закрыл_открыл_то_же() {
        val key = ByteArray(32) { it.toByte() }
        val sealed = BookCopyCodecOverKodium.seal(key, copy)!!
        assertEquals(copy, BookCopyCodecOverKodium.open(key, sealed))
    }

    @Test
    fun чужим_ключом_не_открывается() {
        val sealed = BookCopyCodecOverKodium.seal(ByteArray(32) { 1 }, copy)!!
        assertNull(BookCopyCodecOverKodium.open(ByteArray(32) { 2 }, sealed))
    }

    @Test
    fun имени_и_номера_в_шифре_не_видно() {
        val sealed = BookCopyCodecOverKodium.seal(ByteArray(32) { 3 }, copy)!!
        val text = sealed.decodeToString()
        assertEquals(false, text.contains("Витя"), "имя контакта видно в шифртексте")
        assertEquals(false, text.contains("9160001122"), "номер виден в шифртексте")
    }
}
