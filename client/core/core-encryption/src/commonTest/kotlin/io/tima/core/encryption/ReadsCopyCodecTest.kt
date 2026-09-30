package io.tima.core.encryption

import io.tima.domain.chat.BookCopy
import io.tima.domain.chat.ReadsCopy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Отметки «просмотрено до» под ключом: закрылись — открылись; чужой ключ и ключ книги не открывают. */
class ReadsCopyCodecTest {

    private val copy = ReadsCopy(revision = 3, device = "T1", marks = mapOf("chat-redmi" to 1_790_000_000_000L, "g-1" to 5L))
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun закрыл_открыл_то_же() {
        val sealed = ReadsCopyCodecOverKodium.seal(key, copy)!!
        assertEquals(copy, ReadsCopyCodecOverKodium.open(key, sealed))
    }

    @Test
    fun чужой_ключ_не_открывает() {
        val sealed = ReadsCopyCodecOverKodium.seal(key, copy)!!
        assertNull(ReadsCopyCodecOverKodium.open(ByteArray(32) { 7 }, sealed))
    }

    @Test
    fun ключ_книги_не_открывает_отметки() {
        // Одна метка на одно назначение: из ключа группы выводятся разные ключи.
        val sealed = ReadsCopyCodecOverKodium.seal(key, copy)!!
        assertNull(BookCopyCodecOverKodium.open(key, sealed))
        val book = BookCopyCodecOverKodium.seal(key, BookCopy(revision = 1, device = "T1", contacts = emptyList(), sections = emptyList()))!!
        assertNull(ReadsCopyCodecOverKodium.open(key, book))
    }
}
