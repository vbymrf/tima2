package io.tima.shared

import io.tima.domain.chat.NoticeTab
import io.tima.feature.shell.Window
import io.tima.feature.shell.WindowTab
import kotlin.test.Test
import kotlin.test.assertEquals

/** Пузыри в шапке (ПЛАН-(ВО) ВО3): число окна — из своих вкладок журнала, переход — на вкладку окна. */
class HeaderNoticeTest {

    @Test
    fun вкладки_журнала_окон() {
        assertEquals(listOf(NoticeTab.Chats, NoticeTab.Calls), noticeTabsOf(Window.Phone))
        assertEquals(listOf(NoticeTab.Channels), noticeTabsOf(Window.Social))
        assertEquals(emptyList(), noticeTabsOf(Window.Activity), "у «Общения» своего журнала нет (Р8)")
    }

    @Test
    fun вкладка_окна_для_уведомления() {
        assertEquals(WindowTab.Calls, windowTabOf(NoticeTab.Calls))
        assertEquals(WindowTab.Catalogue, windowTabOf(NoticeTab.Channels))
        assertEquals(WindowTab.Groups, windowTabOf(NoticeTab.Groups))
    }
}

/** Нажатие на строку уведомления (ПЛАН-(МП) М8): одна сущность — в неё, несколько — на вкладку. */
class NoticeTapTest {

    @Test
    fun код_туда_и_обратно() {
        assertEquals(NoticeTab.Chats to "c-1", NoticeTaps.parse(NoticeTaps.code(NoticeTab.Chats, "c-1")))
        assertEquals(NoticeTab.Groups to null, NoticeTaps.parse(NoticeTaps.code(NoticeTab.Groups, null)))
        assertEquals(null, NoticeTaps.parse("нет такой"))
    }

    @Test
    fun окно_вкладки_уведомлений() {
        assertEquals(Window.Phone, noticeWindowOf(NoticeTab.Calls))
        assertEquals(Window.Social, noticeWindowOf(NoticeTab.Channels))
        assertEquals(Window.Page, noticeWindowOf(NoticeTab.Groups))
    }
}
