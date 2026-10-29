package io.tima.core.database

import io.tima.core.outbox.Outbox
import io.tima.core.outbox.SendOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Беда 2026-10-29 «сообщения в прошлом»: часы ПК спешили на 4 мин 40 с, его ссылки вставали «в будущее», а новое
 * с телефона — выше них. Место в переписке — по часам сервера: у своего — время написания по поправке на сервер,
 * после отправки — время приёма сервером; у входящего — время приёма сервером.
 */
class ServerTimeOrderTest {

    private val db = testDatabase()
    private val store = SqlOutboxStore(db, testCipher())

    /** Часы устройства отстают от сервера на минуту. */
    private var device = 1_000_000L
    private val outbox = Outbox(store, nowMs = { device }, stampMs = { device + 60_000 })

    private fun incoming(id: Long, serverAt: Long) {
        db.messagesQueries.insertIncoming(
            dedup_key = "chat-1/$id", server_id = id, chat_id = "chat-1", sender_id = "pc",
            client_ts = device, server_ts = serverAt, state = 0, attempts = 0, body_enc = byteArrayOf(1),
        )
    }

    private fun order() = db.messagesQueries.chatPage("chat-1", 10).executeAsList().map { it.dedup_key }.reversed()

    @Test
    fun время_написания_своего_по_часам_сервера() {
        outbox.enqueue("моё", "chat-1", byteArrayOf(1))
        assertEquals(device + 60_000, store.byDedupKey("моё")!!.createdAtMs)
    }

    @Test
    fun отправленное_встаёт_по_времени_приёма_сервером() {
        outbox.enqueue("моё", "chat-1", byteArrayOf(1))
        outbox.sealNext("chat-1", 1) { byteArrayOf(2) }
        outbox.claimForSend()
        outbox.onOutcome("моё", SendOutcome.Accepted(serverMessageId = 7, receivedAtMs = 5_000))
        assertEquals(5_000L, db.messagesQueries.byDedupKey("моё").executeAsOne().server_ts)
    }

    @Test
    fun спешащие_часы_собеседника_не_ставят_его_сообщение_в_будущее() {
        // Ссылка с ПК: сервер принял её раньше, чем на телефоне написали ответ.
        incoming(1, serverAt = device + 30_000)
        outbox.enqueue("ответ", "chat-1", byteArrayOf(1))
        assertEquals(listOf("chat-1/1", "ответ"), order(), "ответ — ниже ссылки, а не «в прошлом»")
    }
}
