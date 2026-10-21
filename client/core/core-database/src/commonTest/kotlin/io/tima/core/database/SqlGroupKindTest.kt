package io.tima.core.database

import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.GroupKind
import io.tima.domain.chat.ObserveChats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Вид группы на устройстве (миграция 23 → 24, заказчик 2026-10-21): пишет сверка групп, строка списка его
 * отдаёт, обновление имени его не стирает.
 */
class SqlGroupKindTest {

    private val db = testDatabase()
    private val cipher = testCipher()
    private val book = SqlChatBook(db, cipher)
    private val chats = ObserveChats(SqlChatsFeed(db, Codec, cipher, "u-я"))

    @Test
    fun вид_пишется_и_переживает_обновление_имени() = runTest {
        book.remember("g-1", ChatKind.Group, "Поход", null)
        assertNull(chats.list().first().single().groupKind, "не сверялась — вида нет")
        book.rememberGroupKind("g-1", GroupKind.Public)
        assertEquals(GroupKind.Public, chats.list().first().single().groupKind)
        book.remember("g-1", ChatKind.Group, "Поход в горы", null)
        assertEquals(GroupKind.Public, chats.list().first().single().groupKind, "имя обновилось — вид остался")
    }
}
