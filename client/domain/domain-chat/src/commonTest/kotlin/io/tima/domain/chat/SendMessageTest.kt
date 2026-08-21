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

    private class Очередь(val занятые: Set<String> = emptySet()) : OutgoingQueue {
        val поставленные = mutableListOf<Triple<String, String, ByteArray>>()
        override fun enqueue(dedupKey: String, chatId: String, body: ByteArray): Boolean {
            поставленные += Triple(dedupKey, chatId, body)
            return dedupKey !in занятые
        }
    }

    private class Кодек(val размер: Int? = null) : MessageBodyCodec {
        var вызовов = 0
        override fun encodeText(text: String): ByteArray {
            вызовов++
            return размер?.let { ByteArray(it) } ?: ("zstd:$text").encodeToByteArray()
        }
    }

    private class Ключи(vararg ключи: String) : DedupKeys {
        private val список = ключи.toMutableList()
        var выдано = 0
        override fun newKey(): String {
            выдано++
            return список.removeFirst()
        }
    }

    @Test
    fun сообщение_становится_в_очередь_с_ключом_и_телом() {
        val очередь = Очередь()
        val кодек = Кодек()
        val отправка = SendMessage(очередь, кодек, Ключи("dedup-1"))

        val исход = отправка.send("chat-1", "привет")

        assertEquals(SendMessageResult.Queued("dedup-1"), исход)
        val (ключ, чат, тело) = очередь.поставленные.single()
        assertEquals("dedup-1", ключ)
        assertEquals("chat-1", чат)
        assertContentEquals("zstd:привет".encodeToByteArray(), тело)
    }

    @Test
    fun тело_собирается_ровно_один_раз() {
        // Один кодек на провод и на диск: собери тело дважды — и однажды соберёшь
        // по-разному, а подпись считается по байтам.
        val кодек = Кодек()
        SendMessage(Очередь(), кодек, Ключи("d-1")).send("chat-1", "привет")
        assertEquals(1, кодек.вызовов)
    }

    @Test
    fun пустое_сообщение_не_доходит_ни_до_кодека_ни_до_очереди() {
        // «Отправилось ничего» — это ещё и строка в переписке, которую человек не
        // просил. И ключ на неё тратить незачем.
        val очередь = Очередь()
        val кодек = Кодек()
        val ключи = Ключи("d-1")
        val отправка = SendMessage(очередь, кодек, ключи)

        for (пустое in listOf("", " ", "\n", "\t  \n")) {
            assertEquals(SendMessageResult.Empty, отправка.send("chat-1", пустое))
        }

        assertTrue(очередь.поставленные.isEmpty())
        assertEquals(0, кодек.вызовов)
        assertEquals(0, ключи.выдано, "ключ идемпотентности — не бесплатный расходник")
    }

    @Test
    fun слишком_большое_тело_отсекается_до_очереди() {
        // Дешёвая отсечка: точный запас неизвестен (конверт это тело плюс обёртка ключа
        // на каждое устройство получателя), последнее слово у транспорта.
        val очередь = Очередь()
        val отправка = SendMessage(очередь, Кодек(размер = 1_001), Ключи("d-1"), maxBodyBytes = 1_000)

        val исход = отправка.send("chat-1", "очень длинный текст")

        assertIs<SendMessageResult.TooLarge>(исход)
        assertEquals(1_001, исход.bytes)
        assertEquals(1_000, исход.limit)
        assertTrue(очередь.поставленные.isEmpty(), "в очередь такое кладти незачем")
    }

    @Test
    fun ровно_по_пределу_проходит() {
        // Граница включительно: иначе предел, названный в сообщении об ошибке, врал бы
        // на единицу.
        val отправка = SendMessage(Очередь(), Кодек(размер = 1_000), Ключи("d-1"), maxBodyBytes = 1_000)
        assertIs<SendMessageResult.Queued>(отправка.send("chat-1", "текст"))
    }

    @Test
    fun уже_стоящее_в_очереди_не_ошибка() {
        // Так выглядит повторное нажатие и восстановление после перезапуска.
        val отправка = SendMessage(Очередь(занятые = setOf("d-1")), Кодек(), Ключи("d-1"))
        assertEquals(SendMessageResult.AlreadyQueued("d-1"), отправка.send("chat-1", "привет"))
    }

    @Test
    fun каждое_сообщение_получает_свой_ключ() {
        // Один ключ на два сообщения означал бы, что второе сервер отбросит как повтор.
        val очередь = Очередь()
        val отправка = SendMessage(очередь, Кодек(), Ключи("d-1", "d-2"))

        отправка.send("chat-1", "первое")
        отправка.send("chat-1", "второе")

        assertEquals(listOf("d-1", "d-2"), очередь.поставленные.map { it.first })
    }

    @Test
    fun пустой_чат_и_пустой_ключ_это_ошибки_кода_а_не_исходы() {
        // Пустой chatId и пустой ключ приходят не от человека, а от неверного вызова:
        // такое обязано падать сразу, а не превращаться в исход, который кто-то покажет
        // человеку.
        assertFailsWith<IllegalArgumentException> {
            SendMessage(Очередь(), Кодек(), Ключи("d-1")).send("", "привет")
        }
        assertFailsWith<IllegalArgumentException> {
            SendMessage(Очередь(), Кодек(), Ключи("")).send("chat-1", "привет")
        }
    }
}
