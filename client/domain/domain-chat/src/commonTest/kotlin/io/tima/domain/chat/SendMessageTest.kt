package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Правила отправки. Ни сети, ни базы: проверяется именно порядок и именно правила.
 */
class SendMessageTest {

    private class Queue(val taken: Set<String> = emptySet()) : OutgoingQueue {
        val put = mutableListOf<Triple<String, String, ByteArray>>()
        override fun enqueue(dedupKey: String, chatId: String, body: ByteArray, level: Int): Boolean {
            put += Triple(dedupKey, chatId, body)
            return dedupKey !in taken
        }
    }

    private class Codec(val size: Int? = null) : MessageBodyCodec {
        var calls = 0
        override fun encodeText(text: String): ByteArray {
            calls++
            return size?.let { ByteArray(it) } ?: ("zstd:$text").encodeToByteArray()
        }

        // Обратный ход здесь не проверяется: SendMessage только пишет. Подделка честная —
        // снимает ту же приставку, которую ставит.
        override fun decodeText(body: ByteArray): String? =
            body.decodeToString().removePrefix("zstd:")
    }

    private class Keys(vararg keys: String) : DedupKeys {
        private val list = keys.toMutableList()
        var issued = 0
        override fun newKey(): String {
            issued++
            return list.removeFirst()
        }
    }

    @Test
    fun сообщение_становится_в_очередь_с_ключом_и_телом() {
        val queue = Queue()
        val codec = Codec()
        val send = SendMessage(queue, codec, Keys("dedup-1"))

        val outcome = send.send("chat-1", "привет")

        assertEquals(SendMessageResult.Queued("dedup-1"), outcome)
        val (key, chat, body) = queue.put.single()
        assertEquals("dedup-1", key)
        assertEquals("chat-1", chat)
        assertContentEquals("zstd:привет".encodeToByteArray(), body)
    }

    @Test
    fun тело_собирается_ровно_один_раз() {
        // Один кодек на провод и на диск: собери тело дважды — и однажды соберёшь
        // по-разному, а подпись считается по байтам.
        val codec = Codec()
        SendMessage(Queue(), codec, Keys("d-1")).send("chat-1", "привет")
        assertEquals(1, codec.calls)
    }

    @Test
    fun пустое_сообщение_не_доходит_ни_до_кодека_ни_до_очереди() {
        // «Отправилось ничего» — это ещё и строка в переписке, которую человек не
        // просил. И ключ на неё тратить незачем.
        val queue = Queue()
        val codec = Codec()
        val keys = Keys("d-1")
        val send = SendMessage(queue, codec, keys)

        for (empty in listOf("", " ", "\n", "\t  \n")) {
            assertEquals(SendMessageResult.Empty, send.send("chat-1", empty))
        }

        assertTrue(queue.put.isEmpty())
        assertEquals(0, codec.calls)
        assertEquals(0, keys.issued, "ключ идемпотентности — не бесплатный расходник")
    }

    @Test
    fun слишком_большое_тело_отсекается_до_очереди() {
        // Дешёвая отсечка: точный запас неизвестен (конверт это тело плюс обёртка ключа
        // на каждое устройство получателя), последнее слово у транспорта.
        val queue = Queue()
        val send = SendMessage(queue, Codec(size = 1_001), Keys("d-1"), maxBodyBytes = 1_000)

        val outcome = send.send("chat-1", "очень длинный текст")

        assertIs<SendMessageResult.TooLarge>(outcome)
        assertEquals(1_001, outcome.bytes)
        assertEquals(1_000, outcome.limit)
        assertTrue(queue.put.isEmpty(), "в очередь такое кладти незачем")
    }

    @Test
    fun ровно_по_пределу_проходит() {
        // Граница включительно: иначе предел, названный в сообщении об ошибке, врал бы
        // на единицу.
        val send = SendMessage(Queue(), Codec(size = 1_000), Keys("d-1"), maxBodyBytes = 1_000)
        assertIs<SendMessageResult.Queued>(send.send("chat-1", "текст"))
    }

    @Test
    fun уже_стоящее_в_очереди_не_ошибка() {
        // Так выглядит повторное нажатие и восстановление после перезапуска.
        val send = SendMessage(Queue(taken = setOf("d-1")), Codec(), Keys("d-1"))
        assertEquals(SendMessageResult.AlreadyQueued("d-1"), send.send("chat-1", "привет"))
    }

    @Test
    fun каждое_сообщение_получает_свой_ключ() {
        // Один ключ на два сообщения означал бы, что второе сервер отбросит как повтор.
        val queue = Queue()
        val send = SendMessage(queue, Codec(), Keys("d-1", "d-2"))

        send.send("chat-1", "первое")
        send.send("chat-1", "второе")

        assertEquals(listOf("d-1", "d-2"), queue.put.map { it.first })
    }

    @Test
    fun пустой_чат_и_пустой_ключ_это_ошибки_кода_а_не_исходы() {
        // Пустой chatId и пустой ключ приходят не от человека, а от неверного вызова:
        // такое обязано падать сразу, а не превращаться в исход, который кто-то покажет
        // человеку.
        assertFailsWith<IllegalArgumentException> {
            SendMessage(Queue(), Codec(), Keys("d-1")).send("", "привет")
        }
        assertFailsWith<IllegalArgumentException> {
            SendMessage(Queue(), Codec(), Keys("")).send("chat-1", "привет")
        }
    }
}
