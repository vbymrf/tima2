package io.tima.core.database

import io.tima.core.outbox.IncomingEntry
import io.tima.core.outbox.IncomingState
import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.OutboxState
import io.tima.domain.chat.ChatKind
import io.tima.domain.chat.MarkRead
import io.tima.domain.chat.MessageDisplay
import io.tima.domain.chat.ObserveChats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Список переписок: выводится из сообщений, имя добавляется отдельно.
 *
 * Проверяется на настоящей базе, потому что весь список — это один запрос: порядок,
 * превью, число непрочитанных и связь с таблицей имён держатся на SQL, а не на коде
 * рядом с ним.
 */
class SqlChatsFeedTest {

    private val db = testDatabase()
    private val шифр = тестовыйШифр()
    private val outbox = SqlOutboxStore(db, шифр)
    private val inbox = SqlInboxStore(db, шифр)
    private val chats = ObserveChats(SqlChatsFeed(db, Кодек, шифр, Я))

    private fun исходящее(chatId: String, текст: String, ts: Long, state: OutboxState = OutboxState.SENT) {
        outbox.putIfAbsent(
            OutboxEntry(
                dedupKey = "d-$chatId-$ts",
                chatId = chatId,
                body = Кодек.encodeText(текст),
                createdAtMs = ts,
            ),
        )
        val запись = outbox.byDedupKey("d-$chatId-$ts")!!
        outbox.update(запись.copy(state = state))
    }

    private fun входящее(
        chatId: String,
        messageId: Long,
        текст: String?,
        ts: Long,
        state: IncomingState = IncomingState.STORED,
        автор: String = СОБЕСЕДНИК,
    ) {
        inbox.putIfAbsent(
            IncomingEntry(
                chatId = chatId,
                messageId = messageId,
                envelope = Кодек.НЕЧИТАЕМОЕ,
                receivedAtMs = ts,
            ),
        )
        if (текст != null) inbox.storeParsed(chatId, messageId, Кодек.encodeText(текст), автор)
        val запись = inbox.byKey(chatId, messageId)!!
        inbox.update(запись.copy(state = state))
    }

    private fun имя(chatId: String, имя: String, kind: Long = 0, peer: String? = "u-1") {
        db.chatsQueries.upsertChat(
            chat_id = chatId,
            kind = kind,
            title_enc = шифр.seal(имя.encodeToByteArray()),
            peer_id = peer,
        )
    }

    // ── список выводится из сообщений ────────────────────────────────────────

    /**
     * Переписка попадает в список **без строки имени**.
     *
     * Это главное свойство: строка `chats` добавляет имя, а не создаёт переписку. Иначе
     * забытый вызов «завести чат» терял бы человеку всю переписку, и выглядело бы это как
     * «сообщение не пришло».
     */
    @Test
    fun переписка_видна_даже_без_имени() = runTest {
        исходящее("chat-1", "привет", ts = 1_000)

        val список = chats.list().first()

        assertEquals(1, список.size)
        assertEquals("chat-1", список.single().chatId)
        assertNull(список.single().title, "имени нет — и выдумывать его нечем")
        assertEquals("привет", список.single().preview)
    }

    @Test
    fun имя_расшифровывается_и_вид_переписки_читается() = runTest {
        исходящее("chat-1", "привет", ts = 1_000)
        имя("chat-1", "Аня Борисова", kind = 0, peer = "u-аня")
        исходящее("chat-2", "всем привет", ts = 2_000)
        имя("chat-2", "Поход", kind = 1, peer = null)

        val список = chats.list().first().associateBy { it.chatId }

        assertEquals("Аня Борисова", список["chat-1"]?.title)
        assertEquals(ChatKind.Personal, список["chat-1"]?.kind)
        assertEquals("u-аня", список["chat-1"]?.peerId)
        assertEquals("Поход", список["chat-2"]?.title)
        assertEquals(ChatKind.Group, список["chat-2"]?.kind)
        assertNull(список["chat-2"]?.peerId, "у группы собеседника нет")
    }

    /** Имя лежит в базе закрытым: это содержимое переписки, а не метаданные. */
    @Test
    fun имя_в_базе_закрыто() = runTest {
        исходящее("chat-1", "привет", ts = 1_000)
        имя("chat-1", "Аня Борисова")

        val строка = db.chatsQueries.chatById("chat-1").executeAsOne()

        assertTrue(
            !строка.title_enc.contentEquals("Аня Борисова".encodeToByteArray()),
            "имя собеседника лежит открытым",
        )
        assertEquals("Аня Борисова", шифр.open(строка.title_enc!!)?.decodeToString())
    }

    // ── порядок и превью ────────────────────────────────────────────────────

    @Test
    fun сверху_та_переписка_где_сказали_последним() = runTest {
        исходящее("старая", "давно", ts = 1_000)
        исходящее("новая", "только что", ts = 5_000)
        исходящее("средняя", "вчера", ts = 3_000)

        val порядок = chats.list().first().map { it.chatId }

        assertEquals(listOf("новая", "средняя", "старая"), порядок)
    }

    @Test
    fun превью_и_состояние_берутся_у_последнего_сообщения() = runTest {
        исходящее("chat-1", "первое", ts = 1_000)
        исходящее("chat-1", "последнее", ts = 2_000, state = OutboxState.DEAD)

        val строка = chats.list().first().single()

        assertEquals("последнее", строка.preview)
        assertEquals(MessageDisplay.FAILED, строка.lastDisplay, "человек видит, что оно не дошло")
        assertTrue(строка.lastOutgoing)
        assertEquals(2_000, строка.atMs)
    }

    /**
     * У неразобранного входящего превью нет, но переписка в списке есть.
     *
     * Показывать байты конверта было бы хуже пустоты, а прятать переписку — хуже всего:
     * человек не узнает, что ему написали.
     */
    @Test
    fun неразобранное_входящее_даёт_переписку_без_превью() = runTest {
        входящее("chat-1", 1, текст = null, ts = 1_000, state = IncomingState.RECEIVED)

        val строка = chats.list().first().single()

        assertEquals("chat-1", строка.chatId)
        assertNull(строка.preview)
        assertEquals(MessageDisplay.RECEIVED, строка.lastDisplay)
        assertTrue(!строка.lastOutgoing)
    }

    /**
     * Последнее сообщение со своего второго устройства — своё, и в списке тоже.
     *
     * Правило одно на переписку и на список: разойдись они, человек увидел бы у строки
     * отметку отправки там, где в чате чужой пузырь, — и наоборот.
     */
    @Test
    fun последнее_от_себя_же_считается_своим() = runTest {
        входящее("chat-1", 1, "со телефона", ts = 1_000, автор = Я)

        val строка = chats.list().first().single()

        assertTrue(строка.lastOutgoing, "написанное мною с другого устройства — моё")
        assertEquals("со телефона", строка.preview)
    }

    // ── непрочитанное ───────────────────────────────────────────────────────

    @Test
    fun непрочитанное_считается_только_по_входящим() = runTest {
        входящее("chat-1", 1, "раз", ts = 1_000)
        входящее("chat-1", 2, "два", ts = 2_000)
        исходящее("chat-1", "своё", ts = 3_000)

        assertEquals(
            2,
            chats.list().first().single().unread,
            "своё сообщение непрочитанным не бывает",
        )
    }

    @Test
    fun прочитанное_из_счётчика_уходит() = runTest {
        входящее("chat-1", 1, "раз", ts = 1_000, state = IncomingState.READ)
        входящее("chat-1", 2, "два", ts = 2_000)

        assertEquals(1, chats.list().first().single().unread)
    }

    /** Нечитаемое входящее — тоже непрочитанное: человек про него ещё не знает. */
    @Test
    fun нечитаемое_считается_непрочитанным() = runTest {
        входящее("chat-1", 1, текст = null, ts = 1_000, state = IncomingState.UNDECRYPTABLE)

        assertEquals(1, chats.list().first().single().unread)
    }

    @Test
    fun чужая_переписка_в_счётчик_не_попадает() = runTest {
        входящее("chat-1", 1, "раз", ts = 1_000)
        входящее("chat-2", 2, "два", ts = 2_000)

        val список = chats.list().first().associateBy { it.chatId }

        assertEquals(1, список["chat-1"]?.unread)
        assertEquals(1, список["chat-2"]?.unread)
    }

    /**
     * **Открытая переписка гасит счётчик.**
     *
     * До этой правки `markRead` не вызывал никто: янтарная точка висела навсегда, и человек
     * видел «непрочитанное» в переписке, которую только что прочитал.
     */
    @Test
    fun прочтение_переписки_гасит_счётчик() = runTest {
        входящее("chat-1", 1, "раз", ts = 1_000)
        входящее("chat-1", 2, "два", ts = 2_000)
        assertEquals(2, chats.list().first().single().unread)

        val прочитано = MarkRead(SqlReadMarks(io.tima.core.outbox.Inbox(inbox, nowMs = { 3_000 })))
            .chat("chat-1")

        assertEquals(2, прочитано)
        assertEquals(0, chats.list().first().single().unread)
    }

    /**
     * Прочитанным становится **только разобранное**.
     *
     * Неразобранное и нечитаемое человек не читал: у первого текста ещё нет, у второго его
     * может не быть никогда. Погаси их — и человек не узнает, что сообщение было.
     */
    @Test
    fun неразобранное_и_нечитаемое_остаются_непрочитанными() = runTest {
        входящее("chat-1", 1, "разобранное", ts = 1_000)
        входящее("chat-1", 2, текст = null, ts = 2_000, state = IncomingState.RECEIVED)
        входящее("chat-1", 3, текст = null, ts = 3_000, state = IncomingState.UNDECRYPTABLE)

        val прочитано = MarkRead(SqlReadMarks(io.tima.core.outbox.Inbox(inbox, nowMs = { 4_000 })))
            .chat("chat-1")

        assertEquals(1, прочитано, "погасить можно только то, что человек мог прочитать")
        assertEquals(2, chats.list().first().single().unread)
    }

    /** Чужая переписка от прочтения этой не гаснет. */
    @Test
    fun прочтение_не_трогает_другую_переписку() = runTest {
        входящее("chat-1", 1, "раз", ts = 1_000)
        входящее("chat-2", 2, "два", ts = 2_000)

        MarkRead(SqlReadMarks(io.tima.core.outbox.Inbox(inbox, nowMs = { 3_000 }))).chat("chat-1")

        val список = chats.list().first().associateBy { it.chatId }
        assertEquals(0, список["chat-1"]?.unread)
        assertEquals(1, список["chat-2"]?.unread)
    }

    // ── границы и стирание ──────────────────────────────────────────────────

    @Test
    fun страница_ограничена() = runTest {
        repeat(5) { исходящее("chat-$it", "текст", ts = 1_000L + it) }

        assertEquals(2, chats.list(limit = 2).first().size)
        assertFailsWith<IllegalArgumentException> { chats.list(limit = 0) }
        assertFailsWith<IllegalArgumentException> { chats.list(limit = ObserveChats.MAX_PAGE + 1) }
    }

    /**
     * Стёртая переписка уходит из списка вместе с именем.
     *
     * Оставшееся имя было бы не мелочью: человек нажал «удалить переписку», а имя
     * собеседника осталось в базе — то есть удалено не то, что обещали.
     */
    @Test
    fun стёртая_переписка_уходит_вместе_с_именем() = runTest {
        исходящее("chat-1", "привет", ts = 1_000)
        имя("chat-1", "Аня")

        db.messagesQueries.deleteChat("chat-1")
        db.chatsQueries.deleteChatRow("chat-1")

        assertEquals(0, chats.list().first().size)
        assertNull(db.chatsQueries.chatById("chat-1").executeAsOneOrNull())
    }

    private companion object {
        const val Я = "u-я"
        const val СОБЕСЕДНИК = "u-аня"
    }
}
