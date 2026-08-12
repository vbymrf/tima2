package io.tima.app

import io.tima.app.store.LocalDb
import io.tima.app.store.MessageStore
import io.tima.app.store.MsgState
import io.tima.app.store.OutboxAttachment
import io.tima.app.store.StoredChat
import io.tima.app.store.StoredMessage
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageStoreTest {

    private fun store(file: File, secret: ByteArray = ByteArray(32) { it.toByte() }): MessageStore =
        MessageStore(LocalDb(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")), secret)

    private fun tempDbFile(): File =
        File.createTempFile("tima-store-test", ".db").also { it.delete(); it.deleteOnExit() }

    private fun msg(
        cmid: String, chat: String = "chat-1", text: String = "привет",
        state: MsgState = MsgState.QUEUED, at: Long = 1_000,
    ) = StoredMessage(
        chatId = chat, clientMsgId = cmid, senderId = "me",
        createdAtMs = at, state = state, text = text,
    )

    @Test
    fun `переписка переживает перезапуск приложения`() {
        val f = tempDbFile()
        store(f).let { s ->
            s.put(msg("a", text = "первое", at = 1))
            s.put(msg("b", text = "второе", at = 2))
            s.close()
        }
        // Новый объект, новое соединение — как после перезапуска.
        val after = store(f)
        assertEquals(listOf("первое", "второе"), after.messages("chat-1").map { it.text })
        after.close()
    }

    @Test
    fun `текст сообщения не лежит на диске открытым`() {
        val f = tempDbFile()
        val secret = "секрет".encodeToByteArray().copyOf(32)
        store(f, secret).let { s ->
            s.put(msg("a", text = "совершенно секретная строка"))
            s.upsertChat(StoredChat(chatId = "chat-1", title = "Иван Петров", lastText = "совершенно секретная строка"))
            s.close()
        }
        // Смотрим в сам файл базы — так же, как посмотрел бы тот, кто получил телефон.
        val raw = f.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse(raw.contains("совершенно секретная строка"), "текст сообщения виден в файле базы")
        assertFalse(raw.contains("Иван Петров"), "имя собеседника видно в файле базы")
        // А метаданные видны намеренно — по ним ищем и стираем по сроку.
        assertTrue(raw.contains("chat-1"), "chat_id должен оставаться открытым (ADR-0016)")
    }

    @Test
    fun `чужим ключом содержимое не читается`() {
        val f = tempDbFile()
        store(f, ByteArray(32) { 1 }).let { s -> s.put(msg("a", text = "тайна")); s.close() }
        // Другой секрет устройства — содержимое не открывается, но приложение не падает
        // и остальная переписка остаётся доступной.
        val alien = store(f, ByteArray(32) { 2 })
        assertEquals("", alien.messages("chat-1").single().text)
        alien.close()
    }

    @Test
    fun `повторная запись того же сообщения не создаёт дубля`() {
        val s = store(tempDbFile())
        s.put(msg("одинаковый", text = "раз"))
        s.put(msg("одинаковый", text = "два", state = MsgState.SENT))
        // Догон истории пересекается с live-потоком постоянно; дубли были бы видны глазом.
        val all = s.messages("chat-1")
        assertEquals(1, all.size)
        assertEquals("два", all.single().text)
        assertEquals(MsgState.SENT, all.single().state)
        s.close()
    }

    @Test
    fun `очередь переживает перезапуск и отдаётся в порядке написания`() {
        val f = tempDbFile()
        store(f).let { s ->
            s.put(msg("c", text = "третье", at = 30))
            s.put(msg("a", text = "первое", at = 10))
            s.put(msg("b", text = "второе", at = 20))
            s.put(msg("d", text = "отправленное", state = MsgState.SENT, at = 5))
            s.close()
        }
        val after = store(f)
        assertEquals(listOf("первое", "второе", "третье"), after.queued().map { it.text })
        after.close()
    }

    @Test
    fun `зависшее в отправке возвращается в очередь при запуске`() {
        val f = tempDbFile()
        store(f).let { s ->
            s.put(msg("a", state = MsgState.SENDING))
            s.close()
        }
        // Приложение убили посреди отправки. Без этого сообщение осталось бы
        // в «отправляется» навсегда и не ушло бы никогда.
        val after = store(f)
        assertEquals(1, after.requeueStuck())
        assertEquals(1, after.queued().size)
        after.close()
    }

    @Test
    fun `стирание чата убирает и переписку`() {
        val s = store(tempDbFile())
        s.put(msg("a", chat = "chat-1"))
        s.put(msg("b", chat = "chat-2"))
        s.upsertChat(StoredChat(chatId = "chat-1", title = "первый"))
        s.deleteChat("chat-1")
        assertTrue(s.messages("chat-1").isEmpty())
        assertTrue(s.chats().none { it.chatId == "chat-1" })
        // Соседний чат не тронут.
        assertEquals(1, s.messages("chat-2").size)
        s.close()
    }

    @Test
    fun `вложение ждёт в очереди вместе с байтами и переживает перезапуск`() {
        val f = tempDbFile()
        val file = ByteArray(200_000) { (it % 251).toByte() }
        val id = store(f).let { s ->
            val localId = s.put(
                msg("photo", text = "подпись").copy(
                    kind = 3,
                    attachment = OutboxAttachment(mime = "image/jpeg", sizeBytes = file.size.toLong()),
                ),
                attachmentBytes = file,
            )
            s.close()
            localId
        }
        val after = store(f)
        val queued = after.queued().single()
        assertEquals(3, queued.kind)
        assertEquals("image/jpeg", queued.attachment?.mime)
        // Байты в общую выборку не попадают — иначе открытие чата поднимало бы
        // в память все вложения разом.
        assertTrue(after.messages("chat-1").single().attachment?.mime == "image/jpeg")
        assertContentEquals(file, after.attachmentBytes(id))
        after.close()
    }

    @Test
    fun `после выгрузки байты стёрты, а ссылка сохранена`() {
        val f = tempDbFile()
        val s = store(f)
        val id = s.put(
            msg("photo").copy(kind = 3, attachment = OutboxAttachment(mime = "image/jpeg")),
            attachmentBytes = ByteArray(50_000) { 7 },
        )
        s.attachmentUploaded(id, "media-1|a2V5|image/jpeg|50000|0", "подпись")
        // Повторная попытка отправки не должна выкладывать файл заново: по мобильной
        // сети это минута работы и трафик.
        assertEquals(null, s.attachmentBytes(id))
        val m = s.messages("chat-1").single()
        assertEquals("media-1|a2V5|image/jpeg|50000|0", m.mediaJson)
        assertEquals("подпись", m.text)
        s.close()
    }

    @Test
    fun `смена состояния не стирает байты вложения`() {
        val s = store(tempDbFile())
        val id = s.put(
            msg("photo").copy(kind = 3, attachment = OutboxAttachment(mime = "image/jpeg")),
            attachmentBytes = ByteArray(1000) { 3 },
        )
        // «в очереди → отправляется» проходит через тот же put/UPDATE. Если бы он
        // обнулял байты, файл терялся бы ровно в момент начала отправки.
        s.setState(id, MsgState.SENDING)
        s.requeueStuck()
        assertEquals(1000, s.attachmentBytes(id)?.size)
        s.close()
    }

    @Test
    fun `байты вложения не лежат на диске открытыми`() {
        val f = tempDbFile()
        val marker = "СЕКРЕТНОЕ-СОДЕРЖИМОЕ-ФАЙЛА".encodeToByteArray()
        store(f).let { s ->
            s.put(
                msg("doc").copy(kind = 5, attachment = OutboxAttachment(mime = "text/plain", name = "тайна.txt")),
                attachmentBytes = marker + ByteArray(500),
            )
            s.close()
        }
        val raw = f.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse(raw.contains("СЕКРЕТНОЕ-СОДЕРЖИМОЕ-ФАЙЛА"), "содержимое файла видно в базе")
        assertFalse(raw.contains("тайна.txt"), "имя файла видно в базе")
    }

    @Test
    fun `разметка переживает перезапуск и лежит на диске зашифрованной`() {
        val f = tempDbFile()
        val markup = """{"version":1,"n":[1],"blocks":[{"type":"heading","nodes":[1],"level":1}]}"""
        store(f).let { s ->
            s.put(msg("a", text = "Заголовок").copy(markup = markup))
            s.close()
        }
        val after = store(f)
        assertEquals(markup, after.messages("chat-1").single().markup)

        val raw = f.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertFalse(raw.contains("heading"), "разметка видна в файле базы")
        after.close()
    }

    @Test
    fun `сообщение без разметки хранит пустую строку`() {
        val s = store(tempDbFile())
        s.put(msg("a"))
        assertEquals("", s.messages("chat-1").single().markup)
        s.close()
    }

    @Test
    fun `отметка о прочтении не трогает чужие и неотправленные`() {
        val s = store(tempDbFile())
        s.put(msg("sent-1", state = MsgState.SENT, at = 1).copy(messageId = 100))
        s.put(msg("sent-2", state = MsgState.SENT, at = 2).copy(messageId = 200))
        s.put(msg("queued", state = MsgState.QUEUED, at = 3))
        s.markRead("chat-1", upToMessageId = 100)
        val byId = s.messages("chat-1").associateBy { it.clientMsgId }
        assertEquals(MsgState.READ, byId.getValue("sent-1").state)
        assertEquals(MsgState.SENT, byId.getValue("sent-2").state, "прочитано только до 100")
        assertEquals(MsgState.QUEUED, byId.getValue("queued").state, "ждущее отправки не может быть прочитано")
        s.close()
    }
}
