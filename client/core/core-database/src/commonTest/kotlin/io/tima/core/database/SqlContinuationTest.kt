package io.tima.core.database

import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * «Только продолжение» (Решения §11.3): своё исходящее помечает редактор, входящее — сервер
 * открытым полем; строка ленты несёт пометку, по ней лента прячет сообщение.
 */
class SqlContinuationTest {

    private val db = testDatabase()
    private val cipher = testCipher()
    private val outbox = SqlOutboxStore(db, cipher)
    private val inbox = SqlInboxStore(db, cipher)
    private val marks = SqlContinuationMarks(db)
    private val feed = SqlChatFeed(db, Codec, cipher, "u-я")

    @Test
    fun своё_исходящее_помечается_и_читается_отправкой() {
        outbox.putIfAbsent(OutboxEntry(dedupKey = "c-1", chatId = "g-1", body = Codec.encodeText("закрытое"), state = OutboxState.QUEUED, createdAtMs = 1))
        outbox.putIfAbsent(OutboxEntry(dedupKey = "c-2", chatId = "g-1", body = Codec.encodeText("открытое"), state = OutboxState.QUEUED, createdAtMs = 2))
        assertFalse(marks.marked("c-1"))
        marks.mark("c-1")
        assertTrue(marks.marked("c-1"))
        assertFalse(marks.marked("c-2"))
        assertFalse(marks.marked("нет-такого"))
    }

    @Test
    fun входящее_с_пометкой_доходит_до_строки_ленты() = runTest {
        inbox.putIfAbsent(IncomingEntry(chatId = "g-1", messageId = 5, envelope = Codec.encodeText("x"), state = IncomingState.RECEIVED))
        inbox.storeParsed("g-1", 5, Codec.encodeText("закрытая часть"), "u-2", level = 3, threadRoot = 0, onlyContinuation = true)
        inbox.putIfAbsent(IncomingEntry(chatId = "g-1", messageId = 6, envelope = Codec.encodeText("y"), state = IncomingState.RECEIVED))
        inbox.storeParsed("g-1", 6, Codec.encodeText("обычное"), "u-2", level = 2, threadRoot = 0)
        val lines = feed.page("g-1", 10).first()
        assertEquals(mapOf(5L to true, 6L to false), lines.associate { it.serverId to it.onlyContinuation })
    }
}
