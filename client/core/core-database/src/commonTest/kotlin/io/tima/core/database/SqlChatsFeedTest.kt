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
    private val cipher = testCipher()
    private val outbox = SqlOutboxStore(db, cipher)
    private val inbox = SqlInboxStore(db, cipher)
    private val chats = ObserveChats(SqlChatsFeed(db, Codec, cipher, Me))

    private fun outgoing(chatId: String, text: String, ts: Long, state: OutboxState = OutboxState.SENT) {
        outbox.putIfAbsent(
            OutboxEntry(
                dedupKey = "d-$chatId-$ts",
                chatId = chatId,
                body = Codec.encodeText(text),
                createdAtMs = ts,
            ),
        )
        val entry = outbox.byDedupKey("d-$chatId-$ts")!!
        outbox.update(entry.copy(state = state))
    }

    private fun incoming(
        chatId: String,
        messageId: Long,
        text: String?,
        ts: Long,
        state: IncomingState = IncomingState.STORED,
        author: String = PEER,
    ) {
        inbox.putIfAbsent(
            IncomingEntry(
                chatId = chatId,
                messageId = messageId,
                envelope = Codec.UNREADABLE,
                receivedAtMs = ts,
            ),
        )
        if (text != null) inbox.storeParsed(chatId, messageId, Codec.encodeText(text), author)
        val entry = inbox.byKey(chatId, messageId)!!
        inbox.update(entry.copy(state = state))
    }

    private fun name(chatId: String, name: String, kind: Long = 0, peer: String? = "u-1") {
        db.chatsQueries.upsertChat(
            chat_id = chatId,
            kind = kind,
            title_enc = cipher.seal(name.encodeToByteArray()),
            peer_id = peer,
        )
    }

    // ── пустая переписка ────────────────────────────────────────────────────

    /**
     * **Переписка без сообщений видна.**
     *
     * Группу создают заранее, и до первого сообщения в ней ничего нет. Не показать её значит
     * потерять то, что человек только что сделал своими руками: он нажал «создать», группа
     * есть на сервере, а в списке её нет — и объяснить это нечем.
     *
     * Правило «превью и непрочитанное выводятся из сообщений» при этом не тронуто: у пустой
     * переписки их нет вовсе, и здесь же это проверяется.
     */
    @Test
    fun пустая_переписка_видна_в_списке() = runTest {
        name("g-1", "Поход", kind = 1, peer = null)

        val line = chats.list().first().single()

        assertEquals("g-1", line.chatId)
        assertEquals("Поход", line.title)
        assertEquals(ChatKind.Group, line.kind)
        assertNull(line.preview, "сообщений нет — превью взять негде")
        assertNull(line.atMs, "времени нет: последнего сообщения не существует")
        assertNull(line.lastDisplay, "отметки у пустоты не бывает")
        assertEquals(0, line.unread)
    }

    /**
     * Пустые идут первыми.
     *
     * Времени у них нет, сортировать их вместе с остальными нечем — а появились они только
     * что: человек их и создал. Оказаться в конце длинного списка сразу после создания —
     * то же, что не появиться.
     */
    @Test
    fun пустые_переписки_идут_первыми() = runTest {
        outgoing("chat-1", "привет", ts = 5_000)
        name("g-1", "Поход", kind = 1, peer = null)

        val order = chats.list().first().map { it.chatId }

        assertEquals(listOf("g-1", "chat-1"), order)
    }

    /** Пришло первое сообщение — и переписка перестаёт быть пустой, а не удваивается. */
    @Test
    fun с_первым_сообщением_пустая_становится_обычной() = runTest {
        name("g-1", "Поход", kind = 1, peer = null)
        outgoing("g-1", "всем привет", ts = 1_000)

        val list = chats.list().first()

        assertEquals(1, list.size, "строка обязана быть одна, а не две")
        assertEquals("всем привет", list.single().preview)
        assertEquals(1_000, list.single().atMs)
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
        outgoing("chat-1", "привет", ts = 1_000)

        val list = chats.list().first()

        assertEquals(1, list.size)
        assertEquals("chat-1", list.single().chatId)
        assertNull(list.single().title, "имени нет — и выдумывать его нечем")
        assertEquals("привет", list.single().preview)
    }

    @Test
    fun имя_расшифровывается_и_вид_переписки_читается() = runTest {
        outgoing("chat-1", "привет", ts = 1_000)
        name("chat-1", "Аня Борисова", kind = 0, peer = "u-аня")
        outgoing("chat-2", "всем привет", ts = 2_000)
        name("chat-2", "Поход", kind = 1, peer = null)

        val list = chats.list().first().associateBy { it.chatId }

        assertEquals("Аня Борисова", list["chat-1"]?.title)
        assertEquals(ChatKind.Personal, list["chat-1"]?.kind)
        assertEquals("u-аня", list["chat-1"]?.peerId)
        assertEquals("Поход", list["chat-2"]?.title)
        assertEquals(ChatKind.Group, list["chat-2"]?.kind)
        assertNull(list["chat-2"]?.peerId, "у группы собеседника нет")
    }

    /** Имя лежит в базе закрытым: это содержимое переписки, а не метаданные. */
    @Test
    fun имя_в_базе_закрыто() = runTest {
        outgoing("chat-1", "привет", ts = 1_000)
        name("chat-1", "Аня Борисова")

        val line = db.chatsQueries.chatById("chat-1").executeAsOne()

        assertTrue(
            !line.title_enc.contentEquals("Аня Борисова".encodeToByteArray()),
            "имя собеседника лежит открытым",
        )
        assertEquals("Аня Борисова", cipher.open(line.title_enc!!)?.decodeToString())
    }

    // ── порядок и превью ────────────────────────────────────────────────────

    @Test
    fun сверху_та_переписка_где_сказали_последним() = runTest {
        outgoing("старая", "давно", ts = 1_000)
        outgoing("новая", "только что", ts = 5_000)
        outgoing("средняя", "вчера", ts = 3_000)

        val order = chats.list().first().map { it.chatId }

        assertEquals(listOf("новая", "средняя", "старая"), order)
    }

    @Test
    fun превью_и_состояние_берутся_у_последнего_сообщения() = runTest {
        outgoing("chat-1", "первое", ts = 1_000)
        outgoing("chat-1", "последнее", ts = 2_000, state = OutboxState.DEAD)

        val line = chats.list().first().single()

        assertEquals("последнее", line.preview)
        assertEquals(MessageDisplay.FAILED, line.lastDisplay, "человек видит, что оно не дошло")
        assertTrue(line.lastOutgoing)
        assertEquals(2_000, line.atMs)
    }

    /**
     * У неразобранного входящего превью нет, но переписка в списке есть.
     *
     * Показывать байты конверта было бы хуже пустоты, а прятать переписку — хуже всего:
     * человек не узнает, что ему написали.
     */
    @Test
    fun неразобранное_входящее_даёт_переписку_без_превью() = runTest {
        incoming("chat-1", 1, text = null, ts = 1_000, state = IncomingState.RECEIVED)

        val line = chats.list().first().single()

        assertEquals("chat-1", line.chatId)
        assertNull(line.preview)
        assertEquals(MessageDisplay.RECEIVED, line.lastDisplay)
        assertTrue(!line.lastOutgoing)
    }

    /**
     * Последнее сообщение со своего второго устройства — своё, и в списке тоже.
     *
     * Правило одно на переписку и на список: разойдись они, человек увидел бы у строки
     * отметку отправки там, где в чате чужой пузырь, — и наоборот.
     */
    @Test
    fun последнее_от_себя_же_считается_своим() = runTest {
        incoming("chat-1", 1, "со телефона", ts = 1_000, author = Me)

        val line = chats.list().first().single()

        assertTrue(line.lastOutgoing, "написанное мною с другого устройства — моё")
        assertEquals("со телефона", line.preview)
    }

    // ── непрочитанное ───────────────────────────────────────────────────────

    @Test
    fun непрочитанное_считается_только_по_входящим() = runTest {
        incoming("chat-1", 1, "раз", ts = 1_000)
        incoming("chat-1", 2, "два", ts = 2_000)
        outgoing("chat-1", "своё", ts = 3_000)

        assertEquals(
            2,
            chats.list().first().single().unread,
            "своё сообщение непрочитанным не бывает",
        )
    }

    @Test
    fun прочитанное_из_счётчика_уходит() = runTest {
        incoming("chat-1", 1, "раз", ts = 1_000, state = IncomingState.READ)
        incoming("chat-1", 2, "два", ts = 2_000)

        assertEquals(1, chats.list().first().single().unread)
    }

    /** Нечитаемое входящее — тоже непрочитанное: человек про него ещё не знает. */
    @Test
    fun нечитаемое_считается_непрочитанным() = runTest {
        incoming("chat-1", 1, text = null, ts = 1_000, state = IncomingState.UNDECRYPTABLE)

        assertEquals(1, chats.list().first().single().unread)
    }

    @Test
    fun чужая_переписка_в_счётчик_не_попадает() = runTest {
        incoming("chat-1", 1, "раз", ts = 1_000)
        incoming("chat-2", 2, "два", ts = 2_000)

        val list = chats.list().first().associateBy { it.chatId }

        assertEquals(1, list["chat-1"]?.unread)
        assertEquals(1, list["chat-2"]?.unread)
    }

    /**
     * **Открытая переписка гасит счётчик.**
     *
     * До этой правки `markRead` не вызывал никто: янтарная точка висела навсегда, и человек
     * видел «непрочитанное» в переписке, которую только что прочитал.
     */
    @Test
    fun прочтение_переписки_гасит_счётчик() = runTest {
        incoming("chat-1", 1, "раз", ts = 1_000)
        incoming("chat-1", 2, "два", ts = 2_000)
        assertEquals(2, chats.list().first().single().unread)

        val read = MarkRead(SqlReadMarks(io.tima.core.outbox.Inbox(inbox, nowMs = { 3_000 })))
            .chat("chat-1")

        assertEquals(2, read)
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
        incoming("chat-1", 1, "разобранное", ts = 1_000)
        incoming("chat-1", 2, text = null, ts = 2_000, state = IncomingState.RECEIVED)
        incoming("chat-1", 3, text = null, ts = 3_000, state = IncomingState.UNDECRYPTABLE)

        val read = MarkRead(SqlReadMarks(io.tima.core.outbox.Inbox(inbox, nowMs = { 4_000 })))
            .chat("chat-1")

        assertEquals(1, read, "погасить можно только то, что человек мог прочитать")
        assertEquals(2, chats.list().first().single().unread)
    }

    /** Чужая переписка от прочтения этой не гаснет. */
    @Test
    fun прочтение_не_трогает_другую_переписку() = runTest {
        incoming("chat-1", 1, "раз", ts = 1_000)
        incoming("chat-2", 2, "два", ts = 2_000)

        MarkRead(SqlReadMarks(io.tima.core.outbox.Inbox(inbox, nowMs = { 3_000 }))).chat("chat-1")

        val list = chats.list().first().associateBy { it.chatId }
        assertEquals(0, list["chat-1"]?.unread)
        assertEquals(1, list["chat-2"]?.unread)
    }

    // ── границы и стирание ──────────────────────────────────────────────────

    @Test
    fun страница_ограничена() = runTest {
        repeat(5) { outgoing("chat-$it", "текст", ts = 1_000L + it) }

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
        outgoing("chat-1", "привет", ts = 1_000)
        name("chat-1", "Аня")

        db.messagesQueries.deleteChat("chat-1")
        db.chatsQueries.deleteChatRow("chat-1")

        assertEquals(0, chats.list().first().size)
        assertNull(db.chatsQueries.chatById("chat-1").executeAsOneOrNull())
    }

    private companion object {
        const val Me = "u-я"
        const val PEER = "u-аня"
    }
}
