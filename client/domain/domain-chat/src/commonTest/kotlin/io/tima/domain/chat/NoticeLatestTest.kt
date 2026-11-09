package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Пузырь в шапке ведёт на вкладку последнего пришедшего уведомления (ПЛАН-(ВО) ВО3, Р4). */
class NoticeLatestTest {

    @Test
    fun последнее_пришедшее_по_времени() {
        val counts = NoticeCounts(
            listOf(
                ActiveNotice(NoticeTab.Chats, "аня", NoticeWhat.Message).at(1_000),
                ActiveNotice(NoticeTab.Calls, "петя", NoticeWhat.Missed).at(5_000),
                ActiveNotice(NoticeTab.Channels, "канал", NoticeWhat.Message).at(9_000),
            ),
        )
        assertEquals(NoticeTab.Calls, counts.latest(listOf(NoticeTab.Chats, NoticeTab.Calls)), "пропущенный пришёл позже сообщения")
        assertEquals(NoticeTab.Channels, counts.latest(listOf(NoticeTab.Channels)))
        assertNull(counts.latest(listOf(NoticeTab.Groups)))
    }

    @Test
    fun время_в_равенство_не_входит() {
        assertEquals(ActiveNotice(NoticeTab.Chats, "аня", NoticeWhat.Message).at(1), ActiveNotice(NoticeTab.Chats, "аня", NoticeWhat.Message).at(2))
    }
}
