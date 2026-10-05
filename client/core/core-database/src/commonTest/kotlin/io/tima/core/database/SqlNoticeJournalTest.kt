package io.tima.core.database

import io.tima.domain.chat.ActiveNotice
import io.tima.domain.chat.NoticeCounts
import io.tima.domain.chat.NoticeFrom
import io.tima.domain.chat.NoticeRecord
import io.tima.domain.chat.NoticeTab
import io.tima.domain.chat.NoticeWhat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Журнал уведомлений на настоящем SQL — ПЛАН-(ЖУ)-ЖУРНАЛА-УВЕДОМЛЕНИЙ.md, ЖУ1. */
class SqlNoticeJournalTest {

    private val journal = SqlNoticeJournal(testDatabase())

    private fun message(chat: String, n: Int) =
        NoticeRecord(NoticeTab.Chats, chat, NoticeWhat.Message, "$chat/$n", n.toLong(), NoticeFrom.Live)

    private fun missed(from: String, call: String) =
        NoticeRecord(NoticeTab.Calls, from, NoticeWhat.Missed, call, 1, NoticeFrom.Live)

    @Test
    fun redmi_три_сообщения_и_два_пропущенных_moi_одно() {
        // Пример заказчика 2026-09-30: «Чаты» 2, «Звонки» 1, значок 3, строка redmi — 2.
        repeat(3) { journal.record(message("redmi-chat", it)) }
        journal.record(missed("redmi", "c1"))
        journal.record(missed("redmi", "c2"))
        journal.record(message("moi-chat", 1))

        val counts = NoticeCounts(journal.activeNow())

        assertEquals(2, counts.tab(NoticeTab.Chats))
        assertEquals(1, counts.tab(NoticeTab.Calls))
        assertEquals(3, counts.total)
        assertEquals(2, counts.chat("redmi-chat", peerId = "redmi"))
        assertEquals(1, counts.chat("moi-chat", peerId = "moi"))
    }

    @Test
    fun повтор_того_же_звонка_не_записывается() {
        // ПК 2026-09-30: четыре пропущенных — шестнадцать уведомлений, потому что разрыв
        // ленты и два прохода разом поднимали те же звонки снова.
        assertTrue(journal.record(missed("redmi", "c1")))
        assertFalse(journal.record(missed("redmi", "c1")), "тот же звонок второй раз — повтор")
    }

    @Test
    fun снятие_оставляет_строку_с_причиной() {
        journal.record(message("redmi-chat", 1))
        journal.record(message("moi-chat", 1))

        assertEquals(1, journal.clearEntity(NoticeTab.Chats, "redmi-chat", "просмотрена", 5))

        assertEquals(listOf(ActiveNotice(NoticeTab.Chats, "moi-chat", NoticeWhat.Message)), journal.activeNow())
        assertFalse(journal.record(message("redmi-chat", 1)), "снятая строка осталась и опознаёт повтор")
    }

    @Test
    fun открыли_звонки_снято_всё_на_вкладке() {
        journal.record(missed("redmi", "c1"))
        journal.record(missed("moi", "c2"))

        journal.clearTab(NoticeTab.Calls, "вкладка открыта", 5)

        assertEquals(0, NoticeCounts(journal.activeNow()).tab(NoticeTab.Calls))
    }

    @Test
    fun seen_с_другого_устройства_снимает_один_звонок() {
        journal.record(missed("redmi", "c1"))
        journal.record(missed("redmi", "c2"))

        journal.clearRef(NoticeWhat.Missed, "c1", "seen", 5)
        assertTrue(journal.isActive(NoticeTab.Calls, "redmi", NoticeWhat.Missed), "второй звонок ещё не видели")

        journal.clearRef(NoticeWhat.Missed, "c2", "seen", 6)
        assertFalse(journal.isActive(NoticeTab.Calls, "redmi", NoticeWhat.Missed))
    }

    @Test
    fun уборщик_убирает_только_снятое_и_старое() {
        journal.record(message("redmi-chat", 1))
        journal.record(message("moi-chat", 1))
        journal.clearEntity(NoticeTab.Chats, "redmi-chat", "просмотрена", 10)

        assertEquals(0, journal.purge(beforeMs = 10), "снято в 10 — не старше 10")
        assertEquals(1, journal.purge(beforeMs = 11))
        assertEquals(1, journal.activeNow().size, "неснятое не трогается")
    }
}
