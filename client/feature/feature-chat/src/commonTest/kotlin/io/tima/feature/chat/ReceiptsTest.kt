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

    /** Заказчик 2026-10-21: своё, написанное с другого своего устройства, — с теми же знаками. */
    @Test
    fun своё_с_другого_устройства_с_отметками() {
        val receipt = ChatReceipt(deliveredMs = 200, readMs = 100)
        assertEquals(MarkKind.Read, markOf(MessageDisplay.RECEIVED, 100, receipt, own = true))
        assertEquals(MarkKind.Delivered, markOf(MessageDisplay.RECEIVED, 150, receipt, own = true))
        assertEquals(MarkKind.Left, markOf(MessageDisplay.RECEIVED, 250, receipt, own = true), "на сервере — раз пришло")
        assertEquals(MarkKind.Left, markOf(MessageDisplay.RECEIVED, 100, null, own = true), "группа — отметок нет, одна галочка")
        assertEquals(null, markOf(MessageDisplay.UNREADABLE, 100, receipt, own = true))
    }
}
