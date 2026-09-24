package io.tima.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Автоответ заблокированному — Л14.
 *
 * Проверяется не «ушло ли сообщение», а **четыре решения продукта**, каждое из которых
 * легко отменить одной строкой, ничего не сломав по виду:
 *
 * - незнакомцу не отвечаем — иначе автоответ становится оракулом «этот меня заблокировал»
 *   и чистит список рассыльщику;
 * - убранному не отвечаем — у него переписка видна, и «вас заблокировали» там враньё;
 * - не чаще раза в неделю;
 * - ответ уходит видом `CK_SYSTEM`, а не текстом: иначе фраза уедет на языке
 *   блокирующего.
 */
class AutoReplyBlockedTest {

    private class Queue : OutgoingQueue {
        var kind: Int? = null
            private set
        var sent = 0
            private set

        override fun enqueue(
            dedupKey: String,
            chatId: String,
            body: ByteArray,
            level: Int,
            threadRoot: Long,
            kind: Int,
        ): Boolean {
            this.kind = kind
            sent++
            return true
        }
    }

    private class Facts(var last: Long = 0) : ChatFacts {
        override fun kindOf(chatId: String): ChatKind? = ChatKind.Personal
        override fun peerOf(chatId: String): String? = "u-1"
        override fun knows(chatId: String): Boolean = true
        override fun lastSystemOutMs(chatId: String): Long = last
    }

    private val body = object : MessageBodyCodec {
        override fun encodeText(text: String): ByteArray = text.encodeToByteArray()
        override fun decodeText(body: ByteArray): String = body.decodeToString()
    }

    private var now = 1_000_000L
    private val queue = Queue()
    private val facts = Facts()

    private fun ответчик() = AutoReplyBlocked(
        facts = facts,
        send = SendMessage(queue, body, DedupKeys { "ключ-$now-${queue.sent}" }),
        nowMs = { now },
    )

    private fun строка(list: BookList, known: Boolean) = BookEntry(
        id = BookKey.ofUser("u-1"), userId = "u-1", list = list, known = known,
    )

    @Test
    fun знакомому_из_заблокированных_отвечаем_системным_видом() {
        assertTrue(ответчик().replyTo("chat-1", строка(BookList.Blocked, known = true)))
        // Вид, а не текст: читающий нарисует фразу своим словарём (Л15). Пошли мы текстом —
        // и русский, заблокировавший испанца, прислал бы ему русскую фразу.
        assertEquals(KIND_SYSTEM, queue.kind)
    }

    @Test
    fun незнакомцу_не_отвечаем() {
        // Главное решение этой задачи: автоответ незнакомцу — надёжный оракул «этот меня
        // заблокировал». Рассыльщик шлёт по десяти тысячам ников и через неделю точно
        // знает, кто его отсёк. Мы бесплатно делаем за него работу, которую он сам
        // сделать не может.
        assertTrue(!ответчик().replyTo("chat-1", строка(BookList.Blocked, known = false)))
        assertEquals(0, queue.sent)
    }

    @Test
    fun убранному_не_отвечаем() {
        // У убранного переписка видна, и сообщение он прочтёт. Сказать там «вас
        // заблокировали» было бы враньём.
        assertTrue(!ответчик().replyTo("chat-1", строка(BookList.Removed, known = true)))
        assertTrue(!ответчик().replyTo("chat-1", null), "того, кого нет в книге, не знаем")
        assertEquals(0, queue.sent)
    }

    @Test
    fun не_чаще_раза_в_неделю() {
        val ответ = ответчик()
        assertTrue(ответ.replyTo("chat-1", строка(BookList.Blocked, known = true)))

        // Отметка выводится из самой переписки, а не из отдельного хранилища: устройств
        // у человека несколько, и каждое завело бы своё.
        facts.last = now
        now += 6L * 24 * 60 * 60 * 1000
        assertTrue(!ответ.replyTo("chat-1", строка(BookList.Blocked, known = true)))

        now += 2L * 24 * 60 * 60 * 1000
        assertTrue(ответ.replyTo("chat-1", строка(BookList.Blocked, known = true)))
        assertEquals(2, queue.sent)
    }
}
