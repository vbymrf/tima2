package io.tima.app.store

import io.kodium.ratchet.HKDF
import io.tima.crypto.EnvelopeCipher

/**
 * Локальное хранилище переписки — источник правды для экрана (ADR-0016).
 *
 * Раньше экран ходил за историей в сеть, поэтому без связи не открывался ни один
 * чат. Теперь экран ходит сюда, а сеть наполняет это хранилище в фоне.
 *
 * Разделение полей повторяет серверное (ADR-0015):
 *
 * | Открыто                                   | Под шифром                  |
 * |-------------------------------------------|-----------------------------|
 * | chat_id, время, состояние, идентификаторы | текст, вложения, имя чата   |
 *
 * Это не удобство: искать по времени и стирать по сроку можно только по тем полям,
 * которые видно. Всё остальное лежит под SecretBox на ключе, выведенном из секрета
 * устройства — того самого, что уже под SecretVault.
 */
class MessageStore(private val db: LocalDb, deviceSecret: ByteArray) {

    /**
     * Ключ шифрования содержимого на диске. Отдельная метка HKDF — чтобы он не
     * совпал ни с ключом обёрток, ни с ключом резервных копий: у них разные сроки
     * жизни и разные последствия утечки.
     */
    private val atRestKey: ByteArray = HKDF.deriveSecrets(
        salt = null,
        ikm = deviceSecret,
        info = "tima/local-store/v1".encodeToByteArray(),
        length = 32,
    )

    init {
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS messages (
                local_id      INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id       TEXT    NOT NULL,
                message_id    INTEGER NOT NULL DEFAULT 0,
                client_msg_id TEXT    NOT NULL,
                sender_id     TEXT    NOT NULL,
                is_group      INTEGER NOT NULL DEFAULT 0,
                created_at_ms INTEGER NOT NULL,
                state         INTEGER NOT NULL,
                reply_to      INTEGER NOT NULL DEFAULT 0,
                body_enc      BLOB
            )
            """.trimIndent(),
        )
        // Свой идентификатор уникален: он же защищает от двойной записи при догоне
        // истории, которая пересекается с уже полученным по live-каналу.
        db.exec("CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_cmid ON messages(client_msg_id)")
        db.exec("CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages(chat_id, created_at_ms)")
        // Очередь читается часто и должна быть дешёвой.
        db.exec("CREATE INDEX IF NOT EXISTS idx_messages_queue ON messages(state, created_at_ms)")
        // Вложения в очереди появились позже — доращиваем таблицу на месте, чтобы
        // у тех, кто уже поставил приложение, переписка не пропала.
        ensureColumn("messages", "kind", "INTEGER NOT NULL DEFAULT 1")
        ensureColumn("messages", "attach_enc", "BLOB")
        ensureColumn("messages", "attach_meta_enc", "BLOB")
        // Разметка (ADR-0011, Р5б) — отдельная колонка, а не третье поле в body_enc:
        // испорченная или отсутствующая разметка не должна требовать перепаковки
        // того, что уже устоялось в body_enc.
        ensureColumn("messages", "markup_enc", "BLOB")
        db.exec(
            """
            CREATE TABLE IF NOT EXISTS chats (
                chat_id       TEXT PRIMARY KEY,
                peer_user_id  TEXT    NOT NULL DEFAULT '',
                is_group      INTEGER NOT NULL DEFAULT 0,
                title_enc     BLOB,
                last_text_enc BLOB,
                last_at_ms    INTEGER NOT NULL DEFAULT 0,
                unread        INTEGER NOT NULL DEFAULT 0,
                archived_at   INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    /** Дорастить таблицу столбцом, если его ещё нет. */
    private fun ensureColumn(table: String, column: String, decl: String) {
        val present = db.query("PRAGMA table_info($table)") { it.string(1) }
        if (column !in present) db.exec("ALTER TABLE $table ADD COLUMN $column $decl")
    }

    // ── Шифрование содержимого ──

    private fun seal(plain: String): ByteArray? =
        if (plain.isEmpty()) null
        else EnvelopeCipher.seal(atRestKey, plain.encodeToByteArray()).getOrNull()

    private fun open(blob: ByteArray?): String {
        if (blob == null || blob.isEmpty()) return ""
        // Не расшифровалось — показываем пустоту, а не роняем приложение: одна битая
        // строка не должна закрывать человеку доступ ко всей переписке.
        return EnvelopeCipher.open(atRestKey, blob).getOrNull()?.decodeToString() ?: ""
    }

    /**
     * Текст и вложение лежат одной шифрованной записью. Разделитель — перевод
     * строки И нулевой символ: одного переноса мало, сообщения сплошь и рядом
     * многострочные, и первый же перенос был бы принят за границу. Записан
     * экранированием, а не самим символом: сырой NUL в исходнике делает файл
     * двоичным для git.
     */
    private fun packBody(text: String, mediaJson: String): ByteArray? =
        seal(if (mediaJson.isEmpty()) text else "$text\n\u0000$mediaJson")

    private fun unpackBody(blob: ByteArray?): Pair<String, String> {
        val s = open(blob)
        val at = s.indexOf("\n\u0000")
        return if (at < 0) s to "" else s.substring(0, at) to s.substring(at + 2)
    }

    // ── Сообщения ──

    /**
     * Записать или обновить. Ключ — свой идентификатор сообщения, поэтому повторная
     * запись того же сообщения (догон истории пересёкся с live-потоком) не создаёт
     * дубля, а обновляет то, что уже лежит.
     */
    fun put(m: StoredMessage, attachmentBytes: ByteArray? = null): Long = db.transaction {
        val existing = db.query(
            "SELECT local_id FROM messages WHERE client_msg_id = ?", listOf(m.clientMsgId),
        ) { it.long(0) }.firstOrNull()
        val body = packBody(m.text, m.mediaJson)
        val markup = seal(m.markup)
        val meta = m.attachment?.let { seal("${it.mime}|${it.name}|${it.durationMs}|${it.sizeBytes}") }
        val blob = attachmentBytes?.let { EnvelopeCipher.seal(atRestKey, it).getOrNull() }
        if (existing != null) {
            // Байты вложения при обновлении не трогаем, если их не передали: иначе
            // смена состояния «в очереди → отправляется» стирала бы сам файл.
            db.exec(
                """UPDATE messages SET chat_id=?, message_id=?, sender_id=?, is_group=?,
                   created_at_ms=?, state=?, reply_to=?, body_enc=?, kind=?, attach_meta_enc=?, markup_enc=?
                   WHERE local_id=?""",
                listOf(
                    m.chatId, m.messageId, m.senderId, m.isGroup, m.createdAtMs,
                    m.state.code, m.replyTo, body, m.kind, meta, markup, existing,
                ),
            )
            if (blob != null) {
                db.exec("UPDATE messages SET attach_enc=? WHERE local_id=?", listOf(blob, existing))
            }
            existing
        } else {
            db.insert(
                """INSERT INTO messages
                   (chat_id, message_id, client_msg_id, sender_id, is_group, created_at_ms,
                    state, reply_to, body_enc, kind, attach_enc, attach_meta_enc, markup_enc)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                listOf(
                    m.chatId, m.messageId, m.clientMsgId, m.senderId, m.isGroup,
                    m.createdAtMs, m.state.code, m.replyTo, body, m.kind, blob, meta, markup,
                ),
            )
        }
    }

    /**
     * Байты ждущего вложения. Отдельным запросом и только на отправку: файл бывает
     * на десятки мегабайт, и поднимать его при каждом открытии чата незачем.
     */
    fun attachmentBytes(localId: Long): ByteArray? = db.query(
        "SELECT attach_enc FROM messages WHERE local_id = ?", listOf(localId),
    ) { it.bytes(0) }.firstOrNull()
        ?.let { EnvelopeCipher.open(atRestKey, it).getOrNull() }

    /**
     * Файл выложен — байты больше не нужны, освобождаем место и запоминаем ссылку.
     * После этого повторная попытка отправки НЕ выкладывает файл заново: выгрузка
     * 11 МБ по мобильной сети идёт минуту, и делать её дважды из-за одной неудачной
     * посылки — расточительство.
     */
    fun attachmentUploaded(localId: Long, mediaJson: String, text: String) = db.exec(
        "UPDATE messages SET attach_enc = NULL, body_enc = ? WHERE local_id = ?",
        listOf(packBody(text, mediaJson), localId),
    )

    private fun readRow(r: Row): StoredMessage {
        val (text, media) = unpackBody(r.bytes(9))
        val attach = open(r.bytes(11)).takeIf { it.isNotEmpty() }?.split("|")?.let { p ->
            OutboxAttachment(
                mime = p.getOrElse(0) { "" }, name = p.getOrElse(1) { "" },
                durationMs = p.getOrElse(2) { "0" }.toIntOrNull() ?: 0,
                sizeBytes = p.getOrElse(3) { "0" }.toLongOrNull() ?: 0,
            )
        }
        return StoredMessage(
            localId = r.long(0), chatId = r.string(1), messageId = r.long(2),
            clientMsgId = r.string(3), senderId = r.string(4), isGroup = r.long(5) != 0L,
            createdAtMs = r.long(6), state = MsgState.of(r.long(7).toInt()),
            replyTo = r.long(8), text = text, mediaJson = media,
            kind = r.long(10).toInt().let { if (it == 0) 1 else it }, attachment = attach,
            markup = open(r.bytes(12)),
        )
    }

    // attach_enc в выборку НЕ входит намеренно: иначе открытие чата поднимало бы
    // в память все вложенные файлы разом.
    private val columns =
        "local_id, chat_id, message_id, client_msg_id, sender_id, is_group, created_at_ms, " +
            "state, reply_to, body_enc, kind, attach_meta_enc, markup_enc"

    /** История чата, старые → новые. */
    fun messages(chatId: String, limit: Int = 500): List<StoredMessage> = db.query(
        "SELECT $columns FROM messages WHERE chat_id = ? ORDER BY created_at_ms DESC, local_id DESC LIMIT ?",
        listOf(chatId, limit), ::readRow,
    ).reversed()

    /** Наибольший серверный идентификатор в чате — с него продолжаем догон. */
    fun lastServerMessageId(chatId: String): Long = db.query(
        "SELECT COALESCE(MAX(message_id), 0) FROM messages WHERE chat_id = ?", listOf(chatId),
    ) { it.long(0) }.firstOrNull() ?: 0

    /** Очередь на отправку, в порядке написания. */
    fun queued(limit: Int = 100): List<StoredMessage> = db.query(
        "SELECT $columns FROM messages WHERE state = ? ORDER BY created_at_ms, local_id LIMIT ?",
        listOf(MsgState.QUEUED.code, limit), ::readRow,
    )

    fun setState(localId: Long, state: MsgState) =
        db.exec("UPDATE messages SET state = ? WHERE local_id = ?", listOf(state.code, localId))

    /** Сервер принял: запоминаем его идентификатор и снимаем с очереди. */
    fun markSent(localId: Long, messageId: Long) = db.exec(
        "UPDATE messages SET state = ?, message_id = ? WHERE local_id = ?",
        listOf(MsgState.SENT.code, messageId, localId),
    )

    /**
     * Вернуть зависшие в отправке обратно в очередь. Зовём при запуске: если
     * приложение убили посреди отправки, сообщение осталось бы в SENDING навсегда.
     */
    fun requeueStuck(): Int {
        db.exec(
            "UPDATE messages SET state = ? WHERE state = ?",
            listOf(MsgState.QUEUED.code, MsgState.SENDING.code),
        )
        return db.query("SELECT changes()") { it.long(0) }.firstOrNull()?.toInt() ?: 0
    }

    fun markRead(chatId: String, upToMessageId: Long) = db.exec(
        "UPDATE messages SET state = ? WHERE chat_id = ? AND state = ? AND message_id <= ? AND message_id > 0",
        listOf(MsgState.READ.code, chatId, MsgState.SENT.code, upToMessageId),
    )

    // ── Чаты ──

    fun upsertChat(c: StoredChat) = db.transaction {
        db.exec(
            """INSERT INTO chats (chat_id, peer_user_id, is_group, title_enc, last_text_enc, last_at_ms, unread, archived_at)
               VALUES (?,?,?,?,?,?,?,?)
               ON CONFLICT(chat_id) DO UPDATE SET
                 peer_user_id=excluded.peer_user_id, is_group=excluded.is_group,
                 title_enc=excluded.title_enc, last_text_enc=excluded.last_text_enc,
                 last_at_ms=excluded.last_at_ms, unread=excluded.unread, archived_at=excluded.archived_at""",
            listOf(
                c.chatId, c.peerUserId, c.isGroup, seal(c.title), seal(c.lastText),
                c.lastAtMs, c.unread, c.archivedAtMs,
            ),
        )
    }

    fun chats(): List<StoredChat> = db.query(
        "SELECT chat_id, peer_user_id, is_group, title_enc, last_text_enc, last_at_ms, unread, archived_at " +
            "FROM chats ORDER BY last_at_ms DESC",
    ) { r ->
        StoredChat(
            chatId = r.string(0), peerUserId = r.string(1), isGroup = r.long(2) != 0L,
            title = open(r.bytes(3)), lastText = open(r.bytes(4)),
            lastAtMs = r.long(5), unread = r.long(6).toInt(), archivedAtMs = r.long(7),
        )
    }

    /**
     * Физическое стирание чата: и переписка, и запись о нём. Именно ради надёжного
     * выборочного стирания взята база, а не дописываемый файл (ADR-0016).
     */
    fun deleteChat(chatId: String) = db.transaction {
        db.exec("DELETE FROM messages WHERE chat_id = ?", listOf(chatId))
        db.exec("DELETE FROM chats WHERE chat_id = ?", listOf(chatId))
    }

    /** Стереть всё — выход из аккаунта. */
    fun wipe() = db.transaction {
        db.exec("DELETE FROM messages")
        db.exec("DELETE FROM chats")
    }

    fun close() = db.close()
}
