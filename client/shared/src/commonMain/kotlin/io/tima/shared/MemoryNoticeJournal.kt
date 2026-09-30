package io.tima.shared

import io.tima.domain.chat.ActiveNotice
import io.tima.domain.chat.NoticeJournal
import io.tima.domain.chat.NoticeRecord
import io.tima.domain.chat.NoticeTab
import io.tima.domain.chat.NoticeWhat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Журнал уведомлений в памяти — для проверок и для сборки без базы. Правила те же, что у
 * `SqlNoticeJournal`: повтор по виду и ссылке, снятие помечает, а не удаляет.
 */
class MemoryNoticeJournal : NoticeJournal {

    private class Row(val record: NoticeRecord, var done: String = "", var clearedAt: Long = 0, var clearedBy: String = "")

    private val rows = mutableListOf<Row>()
    private val flow = MutableStateFlow<List<ActiveNotice>>(emptyList())

    private fun changed() {
        flow.value = activeNow()
    }

    override fun record(record: NoticeRecord): Boolean {
        if (rows.any { it.record.what == record.what && it.record.ref == record.ref }) return false
        rows += Row(record)
        changed()
        return true
    }

    override fun done(what: NoticeWhat, ref: String, done: String) {
        rows.firstOrNull { it.record.what == what && it.record.ref == ref }?.done = done
    }

    /** Что сделали с событием — для проверок. */
    fun doneOf(what: NoticeWhat, ref: String): String? = rows.firstOrNull { it.record.what == what && it.record.ref == ref }?.done

    override fun isActive(tab: NoticeTab, entity: String, what: NoticeWhat): Boolean =
        rows.any { it.clearedAt == 0L && it.record.tab == tab && it.record.entity == entity && it.record.what == what }

    private fun clear(by: String, atMs: Long, which: (NoticeRecord) -> Boolean): Int {
        val hit = rows.filter { it.clearedAt == 0L && which(it.record) }
        hit.forEach { it.clearedAt = atMs; it.clearedBy = by }
        if (hit.isNotEmpty()) changed()
        return hit.size
    }

    override fun clearEntity(tab: NoticeTab, entity: String, by: String, atMs: Long): Int =
        clear(by, atMs) { it.tab == tab && it.entity == entity }

    override fun clearTab(tab: NoticeTab, by: String, atMs: Long): Int = clear(by, atMs) { it.tab == tab }

    override fun clearRef(what: NoticeWhat, ref: String, by: String, atMs: Long): Int =
        clear(by, atMs) { it.what == what && it.ref == ref }

    override fun active(): Flow<List<ActiveNotice>> = flow

    override fun activeNow(): List<ActiveNotice> =
        rows.filter { it.clearedAt == 0L }.map { ActiveNotice(it.record.tab, it.record.entity, it.record.what) }.distinct()

    override fun purge(beforeMs: Long): Int {
        val old = rows.filter { it.clearedAt in 1 until beforeMs }
        rows.removeAll(old)
        return old.size
    }
}
