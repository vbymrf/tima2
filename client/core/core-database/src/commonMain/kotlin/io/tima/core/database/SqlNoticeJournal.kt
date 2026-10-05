package io.tima.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.tima.domain.chat.ActiveNotice
import io.tima.domain.chat.NoticeJournal
import io.tima.domain.chat.NoticeRecord
import io.tima.domain.chat.NoticeTab
import io.tima.domain.chat.NoticeWhat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Журнал уведомлений в местной базе — ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ1.
 *
 * Открытым, как и журнал звонков: текста нет, только идентификаторы, времена и слова
 * разбора.
 */
class SqlNoticeJournal(
    private val db: TimaDatabase,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : NoticeJournal {

    private val q get() = db.noticeLogQueries

    override fun record(record: NoticeRecord): Boolean = db.transactionWithResult {
        q.record(record.atMs, record.tab.wire, record.entity, record.what.wire, record.ref, record.from.wire)
        // `INSERT OR IGNORE` молчит о повторе; число изменённых строк — единственный ответ.
        q.changes().executeAsOne() > 0
    }

    override fun done(what: NoticeWhat, ref: String, done: String) {
        q.done(done, what.wire, ref)
    }

    override fun isActive(tab: NoticeTab, entity: String, what: NoticeWhat): Boolean =
        q.isActive(tab.wire, entity, what.wire).executeAsOne() > 0

    override fun clearEntity(tab: NoticeTab, entity: String, by: String, atMs: Long): Int = db.transactionWithResult {
        q.clearEntity(atMs, by, tab.wire, entity)
        q.changes().executeAsOne().toInt()
    }

    override fun clearTab(tab: NoticeTab, by: String, atMs: Long): Int = db.transactionWithResult {
        q.clearTab(atMs, by, tab.wire)
        q.changes().executeAsOne().toInt()
    }

    override fun clearRef(what: NoticeWhat, ref: String, by: String, atMs: Long): Int = db.transactionWithResult {
        q.clearRef(atMs, by, what.wire, ref)
        q.changes().executeAsOne().toInt()
    }

    override fun active(): Flow<List<ActiveNotice>> =
        q.active().asFlow().mapToList(io).map { rows -> rows.mapNotNull(::active) }

    override fun activeNow(): List<ActiveNotice> = q.active().executeAsList().mapNotNull(::active)

    override fun purge(beforeMs: Long): Int = db.transactionWithResult {
        q.purge(beforeMs)
        q.changes().executeAsOne().toInt()
    }

    private fun active(row: Active): ActiveNotice? {
        val tab = NoticeTab.of(row.tab) ?: return null
        val what = NoticeWhat.of(row.what) ?: return null
        return ActiveNotice(tab, row.entity, what)
    }
}
