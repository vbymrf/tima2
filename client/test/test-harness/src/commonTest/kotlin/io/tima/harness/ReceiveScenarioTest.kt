package io.tima.harness

import io.tima.core.database.SqlInboxStore
import io.tima.core.database.TimaDatabase
import io.tima.core.outbox.Inbox
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OpenOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Приём: догон истории и живой канал приносят одно и то же — сообщений должно остаться
 * ровно столько, сколько их отправили.
 *
 * Это названный выход К4.5, и проверяется он на кадрах сервера: внутри машины
 * состояний `event_id` не существует, а именно в нём и разница между догоном и живым
 * кадром.
 */
class ReceiveScenarioTest {

    private val db = TimaDatabase(harnessDriver())
    private val store = SqlInboxStore(db, cipherHarness())
    private val inbox = Inbox(store, nowMs = { 1_000 })
    private val h = ReceiveHarness(inbox)

    private val body = byteArrayOf(7, 7, 7)

    /** Кадр `message.new`, как его составляет сервер. */
    private fun frame(eventId: Long, messageId: Long, chatId: String = "chat-1"): String {
        val envelope = base64url(byteArrayOf(1, 2, 3) + messageId.toByte())
        return """{"event":"message.new","event_id":$eventId,"chat_id":"$chatId",""" +
            """"message_id":$messageId,"envelope":"$envelope"}"""
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun base64url(bytes: ByteArray): String =
        kotlin.io.encoding.Base64.UrlSafe
            .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
            .encode(bytes)

    @Test
    fun догон_и_живой_канал_дают_одно_сообщение() {
        // Одно сообщение, два кадра: у догона свой event_id, у живого свой. Опознавать
        // повтор надо по message_id — его назначил отправитель, и он входит в подпись.
        h.onFrame(frame(eventId = 10, messageId = 555))
        h.onFrame(frame(eventId = 11, messageId = 555))

        assertEquals(1, h.count(), "два кадра одного сообщения — одна запись")
        assertEquals(
            listOf("""{"event":"ack","event_id":10}""", """{"event":"ack","event_id":11}"""),
            h.sent,
            "но подтверждены оба кадра: иначе курсор встанет на повторе",
        )
    }

    @Test
    fun разные_сообщения_не_склеиваются() {
        h.onFrame(frame(eventId = 10, messageId = 1))
        h.onFrame(frame(eventId = 11, messageId = 2))

        assertEquals(2, h.count())
    }

    @Test
    fun одинаковый_номер_в_разных_переписках_это_разные_сообщения() {
        // Ключ уникальности — пара (chat_id, message_id). Номер назначает отправитель, и
        // в другой переписке он вполне может совпасть.
        h.onFrame(frame(eventId = 10, messageId = 5, chatId = "chat-1"))
        h.onFrame(frame(eventId = 11, messageId = 5, chatId = "chat-2"))

        assertEquals(2, h.count())
    }

    @Test
    fun подтверждение_идёт_после_записи_а_не_до() {
        // Проверяется порядком: к моменту, когда подтверждение оказалось в списке
        // отправленного, запись уже обязана существовать. Подтвердить раньше — значит
        // сдвинуть серверный курсор до того, как сообщение оказалось у нас.
        assertTrue(h.sent.isEmpty())

        h.onFrame(frame(eventId = 10, messageId = 555))

        assertEquals(1, h.count(), "запись есть")
        assertEquals(1, h.sent.size, "и подтверждение отправлено ровно одно")
    }

    @Test
    fun испорченный_кадр_не_останавливает_канал() {
        // Курсор обязан двигаться дальше испорченного события, иначе та же партия
        // приходит вечно и канал встаёт целиком.
        h.onFrame("""{"event":"message.new","event_id":10,"chat_id":"chat-1"}""")

        assertEquals(0, h.count(), "записывать нечего")
        assertEquals(listOf("""{"event":"ack","event_id":10}"""), h.sent, "но курсор двинулся")
        assertEquals(1, h.skipped.size)
    }

    @Test
    fun остаток_догона_запрашивается_сам() {
        // more = true означает «есть ещё». Не попросить следующую страницу — значит не
        // получить остаток истории, причём канал будет выглядеть исправным.
        h.onFrame("""{"event":"sync.done","count":100,"next_cursor":250,"more":true}""")

        assertEquals(1, h.sent.size)
        assertTrue(h.sent.single().contains("\"cursor\":250"), "запрошен остаток: ${h.sent}")
    }

    @Test
    fun последняя_страница_догона_ничего_не_запрашивает() {
        h.onFrame("""{"event":"sync.done","count":7,"next_cursor":257,"more":false}""")
        assertTrue(h.sent.isEmpty(), "просить нечего: ${h.sent}")
    }

    @Test
    fun принятое_разбирается_и_нечитаемое_не_теряется() {
        h.onFrame(frame(eventId = 10, messageId = 1))
        h.onFrame(frame(eventId = 11, messageId = 2))

        // Одно расшифровалось, второму нет ключа.
        val parsed = h.openAll { entry ->
            if (entry.messageId == 1L) OpenOutcome.Opened(body, "u-автор") else OpenOutcome.NoKey("нет ключа эпохи")
        }

        assertEquals(2, parsed, "оба получили исход")

        // Состояния смотрим в хранилище, а не в pending: у входящей машины pending
        // означает «требует работы» — это RECEIVED и UNDECRYPTABLE. Разобранное в него
        // не входит, и это правильно: работы с ним больше нет. Ожидание «STORED лежит в
        // pending» было моим, а не машины.
        assertEquals(IncomingState.STORED, store.byKey("chat-1", 1)?.state)
        assertEquals(
            IncomingState.UNDECRYPTABLE,
            store.byKey("chat-1", 2)?.state,
            "нечитаемое остаётся видимым",
        )
        assertEquals(1, inbox.pending().size, "работы осталось ровно на одну запись")
    }
}
