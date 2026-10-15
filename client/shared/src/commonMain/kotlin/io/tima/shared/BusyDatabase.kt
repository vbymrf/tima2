package io.tima.shared

import io.tima.core.diag.Journal
import io.tima.core.diag.LogCode
import io.tima.domain.chat.ReadMarks

/**
 * Отметка «прочитано», не прошедшая из-за занятой базы, — строка в журнал, а не падение.
 *
 * Отчёт SG9S (ПК, 2026-10-14): «[SQLITE_BUSY] The database file is locked» в `markChatRead`, и
 * приложение закрылось само. Отметка не стоит падения: она повторится при следующем открытии
 * переписки, а непрочитанное до тех пор просто остаётся непрочитанным. Прочие ошибки — как были.
 */
internal class BusyTolerantReadMarks(private val inner: ReadMarks) : ReadMarks {
    override fun markChatRead(chatId: String): Int = try {
        inner.markChatRead(chatId)
    } catch (e: Exception) {
        if (!databaseBusy(e)) throw e
        Journal.trouble(LogCode.DB_BUSY, "отметка «прочитано» не записана — база занята; повторится при следующем открытии")
        0
    }
}

/**
 * Ошибка — «база занята»: на ПК `SQLiteException` с `SQLITE_BUSY`, на Android
 * `SQLiteDatabaseLockedException`. Типы платформенные, поэтому — по имени и тексту по всей цепочке.
 */
internal fun databaseBusy(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
    val text = it.message.orEmpty()
    "SQLITE_BUSY" in text || "database is locked" in text || (it::class.simpleName ?: "").contains("DatabaseLocked")
}
