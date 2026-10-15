package io.tima.shared

import io.tima.core.diag.Diary
import io.tima.core.diag.Journal
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Отчёт SG9S (ПК, 2026-10-14): «[SQLITE_BUSY] The database file is locked» в `markChatRead` —
 * и приложение закрылось само. Отметка «прочитано» падения не стоит.
 */
class BusyDatabaseTest {

    private fun journalOf(block: () -> Unit): String {
        val diary = Diary(now = { 1_757_000_000_000L })
        Journal.replace(diary)
        block()
        return diary.dump()
    }

    @Test
    fun занятая_база_даёт_строку_в_журнал_а_не_падение() {
        val busy = BusyTolerantReadMarks {
            throw IllegalStateException("отметка", RuntimeException("[SQLITE_BUSY] The database file is locked (database is locked)"))
        }
        var marked = -1
        val text = journalOf { marked = busy.markChatRead("c-1") }
        assertEquals(0, marked)
        assertContains(text, "DB-BUSY")
    }

    @Test
    fun прочие_ошибки_базы_не_глотаются() {
        val broken = BusyTolerantReadMarks { throw IllegalStateException("no such table: message") }
        assertFailsWith<IllegalStateException> { broken.markChatRead("c-1") }
    }

    @Test
    fun отметка_без_ошибки_идёт_как_была() {
        assertEquals(3, BusyTolerantReadMarks { 3 }.markChatRead("c-1"))
    }
}
