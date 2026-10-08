package io.tima.feature.chat

import io.tima.core.ui.MarkKind
import io.tima.domain.chat.MessageDisplay
import kotlin.test.Test
import kotlin.test.assertEquals

/** Знак своего сообщения по отметкам переписки (ПЛАН-(ОП)): время написания против «до». */
class ReceiptsTest {

    @Test
    fun прочитано_доставлено_отправлено_по_времени_написания() {
        val receipt = ChatReceipt(deliveredMs = 200, readMs = 100)
        assertEquals(MarkKind.Read, markOf(MessageDisplay.SENT, 100, receipt))
        assertEquals(MarkKind.Delivered, markOf(MessageDisplay.SENT, 150, receipt))
        assertEquals(MarkKind.Left, markOf(MessageDisplay.SENT, 250, receipt))
        assertEquals(MarkKind.Left, markOf(MessageDisplay.SENT, 100, null))
        // Ждущее и не ушедшее отметки не меняют.
        assertEquals(MarkKind.Waits, markOf(MessageDisplay.PENDING, 50, receipt))
        assertEquals(MarkKind.NotLeft, markOf(MessageDisplay.FAILED, 50, receipt))
        assertEquals(null, markOf(MessageDisplay.RECEIVED, 50, receipt))
    }
}
