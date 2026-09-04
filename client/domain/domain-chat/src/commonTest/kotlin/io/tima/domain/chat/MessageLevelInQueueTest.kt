package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Круг сообщения доезжает до очереди (ADR-0019, ПЛАН-СОЦИУМА Г7б).
 *
 * Главная ось отправки — **нужен ли ключ**, и ответ на неё живёт в записи очереди: она
 * может пролежать до перезапуска, и решать по памяти экрана было бы нечем.
 */
class MessageLevelInQueueTest {

    @Test
    fun круг_едет_вместе_с_сообщением() {
        val queue = RememberingQueue()
        val send = SendMessage(queue = queue, codec = PlainCodec, keys = { "d-1" })

        val outcome = send.send("g-1", "описание группы", level = 0)

        assertTrue(outcome is SendMessageResult.Queued, "сообщение не встало в очередь: $outcome")
        assertEquals(0, queue.lastLevel, "круг не доехал до очереди")
    }

    @Test
    fun по_умолчанию_шифр() {
        // Прежнее поведение обязано сохраниться: всё, что не назвало круга, — шифр.
        // Иначе первое же сообщение старого экрана ушло бы открытым текстом.
        val queue = RememberingQueue()
        val send = SendMessage(queue = queue, codec = PlainCodec, keys = { "d-2" })

        send.send("chat-1", "привет")

        assertEquals(LEVEL_SECRET, queue.lastLevel, "умолчание перестало быть шифром")
    }

    private class RememberingQueue : OutgoingQueue {
        var lastLevel: Int = Int.MIN_VALUE
            private set

        override fun enqueue(dedupKey: String, chatId: String, body: ByteArray, level: Int): Boolean {
            lastLevel = level
            return true
        }
    }

    private object PlainCodec : MessageBodyCodec {
        override fun encodeText(text: String): ByteArray = text.encodeToByteArray()
        override fun decodeText(body: ByteArray): String? = body.decodeToString()
    }
}
