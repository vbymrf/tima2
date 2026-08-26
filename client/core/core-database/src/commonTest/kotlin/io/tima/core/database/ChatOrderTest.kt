package io.tima.core.database

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Порядок сообщений в чате — правило из Plan.md §3.4.2: по `server_ts`, если он есть,
 * иначе по `client_ts`, при равенстве — по `local_id`.
 *
 * **Зачем отдельный тест.** Без этого правила офлайн-сообщения прыгают в списке в тот
 * момент, когда приходят серверные идентификаторы: сначала они стоят по часам
 * устройства, потом внезапно перескакивают. Заметно это не при отправке, а через
 * секунды — то есть при обычной проверке «отправил и увидел» не видно вовсе.
 */
class ChatOrderTest {

    private val db = testDatabase()
    private val q = db.messagesQueries

    private fun place(id: String, clientTs: Long, serverTs: Long? = null) {
        q.insertQueued(
            dedup_key = id, chat_id = "chat-1", sender_id = "me",
            client_ts = clientTs, state = 0, attempts = 0,
            next_attempt_at = null, reply_to = null, body_enc = byteArrayOf(1),
        )
        if (serverTs != null) {
            q.markSent(state = 3, server_id = 1, server_ts = serverTs, dedup_key = id)
        }
    }

    @Test
    fun серверное_время_сильнее_часов_устройства() {
        // Часы устройства врут: сообщение, составленное «в будущем», не должно
        // навсегда остаться сверху, когда сервер сказал настоящее время.
        place("будущее", clientTs = 9_000, serverTs = 100)
        place("обычное", clientTs = 200)

        val order = q.chatPage("chat-1", 10).executeAsList().map { it.dedup_key }
        assertEquals(listOf("обычное", "будущее"), order)
    }

    @Test
    fun без_серверного_времени_порядок_по_часам_устройства() {
        place("раньше", clientTs = 100)
        place("позже", clientTs = 200)

        val order = q.chatPage("chat-1", 10).executeAsList().map { it.dedup_key }
        assertEquals(listOf("позже", "раньше"), order, "новые сверху")
    }

    @Test
    fun при_равном_времени_решает_свой_идентификатор() {
        // Два сообщения в одну миллисекунду — не редкость при вставке пачкой из
        // догона истории. Без устойчивого признака их порядок менялся бы от запроса
        // к запросу, и список «дёргался» бы без причины.
        place("первое", clientTs = 500)
        place("второе", clientTs = 500)

        val once = q.chatPage("chat-1", 10).executeAsList().map { it.dedup_key }
        val two = q.chatPage("chat-1", 10).executeAsList().map { it.dedup_key }
        assertEquals(listOf("второе", "первое"), once)
        assertEquals(once, two, "порядок обязан быть устойчивым между запросами")
    }

    @Test
    fun чужой_чат_не_попадает_в_выдачу() {
        place("свой", clientTs = 100)
        q.insertQueued(
            dedup_key = "чужой", chat_id = "chat-2", sender_id = "me",
            client_ts = 100, state = 0, attempts = 0,
            next_attempt_at = null, reply_to = null, body_enc = byteArrayOf(1),
        )
        assertEquals(listOf("свой"), q.chatPage("chat-1", 10).executeAsList().map { it.dedup_key })
    }
}
