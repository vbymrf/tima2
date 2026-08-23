package io.tima.core.database

import io.tima.core.outbox.OutboxEntry
import io.tima.core.outbox.FieldCipher
import io.tima.core.outbox.OutboxState
import io.tima.core.outbox.OutboxStore

/**
 * [OutboxStore] поверх таблицы `messages`.
 *
 * **Почему не своя таблица.** По плану (§3.4.2) очередь исходящих — это столбцы
 * `messages`: `state`, `attempts`, `next_attempt_at`, `dedup_key`. Отдельная таблица
 * дала бы у одного сообщения два источника правды, а это ровно тот дефект v1, из-за
 * которого список чатов расходился между SQLite и `chats.json`.
 *
 * Состояние хранится числом — порядковым номером [OutboxState]. Это осознанный
 * компромисс: числа компактны и индексируются, но **переупорядочивание значений
 * enum молча переименует состояния уже записанных строк**. Поэтому порядок
 * [OutboxState] менять нельзя, а добавлять новые значения — только в конец. Проверка
 * этого — тест [io.tima.core.database.OutboxStateOrdinalTest].
 */
class SqlOutboxStore(
    private val db: TimaDatabase,
    /**
     * Шифр покоя. Обязателен: столбец `body_enc` **всегда** закрыт, и «пока без
     * шифрования» тут не бывает — ровно так обещание и теряется.
     */
    private val cipher: FieldCipher,
) : OutboxStore {

    private val q get() = db.messagesQueries

    override fun putIfAbsent(entry: OutboxEntry): Boolean = db.transactionWithResult {
        q.insertQueued(
            dedup_key = entry.dedupKey,
            chat_id = entry.chatId,
            // Отправитель на этом слое не известен: очередь не знает, кто мы. Пишется
            // при постановке из репозитория; пока — пусто, а не выдуманный ноль.
            sender_id = "",
            client_ts = entry.createdAtMs,
            state = entry.state.ordinal.toLong(),
            attempts = entry.attempts.toLong(),
            next_attempt_at = entry.nextAttemptAtMs,
            reply_to = null,
            body_enc = cipher.seal(entry.body),
        )
        // INSERT OR IGNORE молчит при конфликте, поэтому «поставили» отличается от
        // «уже было» только числом затронутых строк. Без этой проверки повторная
        // постановка выглядела бы как успешная и сбрасывала бы счётчик попыток.
        q.changes().executeAsOne() > 0
    }

    override fun byDedupKey(dedupKey: String): OutboxEntry? =
        q.byDedupKey(dedupKey).executeAsOneOrNull()?.toEntry()

    override fun nextQueued(nowMs: Long): OutboxEntry? =
        q.nextQueued(OutboxState.QUEUED.ordinal.toLong(), nowMs).executeAsOneOrNull()?.toEntry()

    override fun nextQueued(chatId: String, nowMs: Long): OutboxEntry? =
        q.nextQueuedInChat(chatId, OutboxState.QUEUED.ordinal.toLong(), nowMs)
            .executeAsOneOrNull()?.toEntry()

    override fun claimSealed(): OutboxEntry? = db.transactionWithResult {
        val row = q.nextSealed(OutboxState.SEALED.ordinal.toLong()).executeAsOneOrNull()
            ?: return@transactionWithResult null
        // Выбор и перевод — в одной транзакции: между ними нельзя оказаться, иначе
        // два вызова возьмут одну запись и сообщение уйдёт дважды.
        val claimed = row.toEntry().copy(state = OutboxState.SENDING)
        writeState(claimed)
        claimed
    }

    override fun update(entry: OutboxEntry) = writeState(entry)

    override fun requeueStuck(): Int = db.transactionWithResult {
        q.requeueStuck(
            state = OutboxState.QUEUED.ordinal.toLong(),
            state_ = OutboxState.SENDING.ordinal.toLong(),
            state__ = OutboxState.SEALED.ordinal.toLong(),
        )
        q.changes().executeAsOne().toInt()
    }

    override fun pending(): List<OutboxEntry> = q.pending(
        state = OutboxState.QUEUED.ordinal.toLong(),
        state_ = OutboxState.SEALED.ordinal.toLong(),
        state__ = OutboxState.SENDING.ordinal.toLong(),
    ).executeAsList().map { it.toEntry() }

    private fun writeState(entry: OutboxEntry) {
        q.updateState(
            state = entry.state.ordinal.toLong(),
            attempts = entry.attempts.toLong(),
            next_attempt_at = entry.nextAttemptAtMs,
            sealed_epoch = entry.sealedForEpoch?.toLong(),
            server_id = entry.serverMessageId,
            dedup_key = entry.dedupKey,
        )
    }

    private fun Messages.toEntry() = OutboxEntry(
        dedupKey = dedup_key,
        chatId = chat_id,
        // Не открылось — значит база не наша: ключ покоя выводится из секрета устройства,
        // и чужой секрет означает чужую установку. Здесь падаем громко, а не отдаём пустое
        // тело: пустое ушло бы на сервер как сообщение. У переписки (SqlChatFeed) правило
        // обратное — там одна нечитаемая строка не должна лишать человека всей истории.
        body = requireNotNull(cipher.open(body_enc)) {
            "тело $dedup_key не открывается ключом покоя: база принадлежит другой установке"
        },
        state = stateOf(state),
        attempts = attempts.toInt(),
        nextAttemptAtMs = next_attempt_at ?: 0,
        createdAtMs = client_ts,
        serverMessageId = server_id,
        sealedForEpoch = sealed_epoch?.toInt(),
    )

    private companion object {
        private val byOrdinal = OutboxState.entries.associateBy { it.ordinal.toLong() }

        /**
         * Число из базы в состояние.
         *
         * Неизвестное значение — **ошибка, а не «сойдёт за QUEUED»**: строка,
         * записанная более новой версией приложения, не должна тихо попасть в
         * очередь и уйти повторно.
         */
        fun stateOf(raw: Long): OutboxState = byOrdinal[raw]
            ?: error("неизвестное состояние очереди: $raw — строка от более новой версии?")
    }
}
